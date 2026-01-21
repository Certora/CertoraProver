use std::collections::HashMap;

use gimli::{
    AttributeValue, DW_AT_byte_size, DW_AT_count, DW_AT_discr, DW_AT_discr_value, DW_AT_encoding,
    DW_ATE_boolean, DW_ATE_float, DW_ATE_signed, DW_ATE_unsigned, DW_TAG_array_type,
    DW_TAG_base_type, DW_TAG_enumeration_type, DW_TAG_enumerator, DW_TAG_member,
    DW_TAG_pointer_type, DW_TAG_structure_type, DW_TAG_subrange_type, DW_TAG_variant,
    DW_TAG_variant_part, DebugInfoOffset, DebuggingInformationEntry, DwAte, Dwarf, EndianSlice,
    RunTimeEndian, UnitOffset, UnitRef,
};
use serde::Serialize;

use crate::{
    die_extensions::{DebugEntryExt, ParsingError, error_from_offset},
    types::{
        data_structures::{CompilationUnit, extract_all_var_type_ids},
        rust_type::{
            Discriminant, EnumVariant, EnumVariantType, RustType, RustTypeId, StructField, Variant,
        },
    },
    worklist::Worklist,
};

#[derive(Serialize)]
pub struct TypeParser<'a> {
    pub type_nodes: HashMap<RustTypeId, RustType>,
    #[serde(skip_serializing)]
    pub worklist: Worklist<RustTypeId>,
    #[serde(skip_serializing)]
    pub dwarf: &'a Dwarf<EndianSlice<'a, RunTimeEndian>>,
    #[serde(skip_serializing)]
    pub parsing_errors: Vec<String>,
}

impl<'a> TypeParser<'a> {
    pub fn new(dwarf: &'a gimli::Dwarf<EndianSlice<'a, RunTimeEndian>>) -> Self {
        TypeParser {
            type_nodes: HashMap::new(),
            worklist: Worklist::new(),
            dwarf: dwarf,
            parsing_errors: Vec::new(),
        }
    }

    /// Parse a type at a specific DWARF offset
    ///
    /// This method follows a type reference to its definition and parses it based
    /// on the DWARF tag (base type, struct, enum, array, etc.).
    fn parse_variable_type_at_offset(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        type_offset: UnitOffset,
    ) -> Result<RustType, ParsingError> {
        let mut tree = unit_ref.entries_tree(Some(type_offset)).unwrap();
        let root = tree.root().unwrap();
        let entry = root.entry();
        let tag = entry.tag();
        let res = match tag {
            DW_TAG_base_type => self.parse_primitive_type(entry),
            DW_TAG_pointer_type => self.parse_pointer_type(unit_ref, entry),
            DW_TAG_array_type => self.parse_array_type(unit_ref, entry),
            DW_TAG_structure_type => self.parse_struct_type(unit_ref, root),
            DW_TAG_enumeration_type => self.parse_enum_type(unit_ref, root),

            // TODO: Add support for union, const, volatile, and typedef types
            // Missing cases: DW_TAG_union_type, DW_TAG_typedef, DW_TAG_const_type,
            // DW_TAG_volatile_type need to be implemented for complete coverage
            _ => Err(error_from_offset(
                &format!("Found an unsupported type {}", tag),
                &type_offset,
            )),
        };
        res
    }

    /// Parse a DWARF base type (primitive types like integers, floats)
    ///
    /// Base types are the fundamental building blocks like i32, f64, etc.
    /// They are identified by their encoding (signed/unsigned/float) and size.
    fn parse_primitive_type(
        &self,
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let mut byte_size: Option<u64> = None;
        let mut encoding: Option<DwAte> = None;

        match entry.attr(DW_AT_byte_size)? {
            Some(attr) => {
                if let AttributeValue::Udata(size) = attr.value() {
                    byte_size = Some(size);
                }
            }
            _ => {}
        }
        match entry.attr(DW_AT_encoding)? {
            Some(attr) => {
                if let AttributeValue::Encoding(enc) = attr.value() {
                    encoding = Some(enc);
                }
            }
            _ => {}
        }

        // Map DWARF encoding and size combinations to specific Rust types
        let res = match (encoding, byte_size) {
            // Signed integer types
            (Some(DW_ATE_signed), Some(1)) => RustType::I8,
            (Some(DW_ATE_signed), Some(2)) => RustType::I16,
            (Some(DW_ATE_signed), Some(4)) => RustType::I32,
            (Some(DW_ATE_signed), Some(8)) => RustType::I64,
            (Some(DW_ATE_signed), Some(16)) => RustType::I128,

            // Unsigned integer types
            (Some(DW_ATE_unsigned), Some(1)) => RustType::U8,
            (Some(DW_ATE_unsigned), Some(2)) => RustType::U16,
            (Some(DW_ATE_unsigned), Some(4)) => RustType::U32,
            (Some(DW_ATE_unsigned), Some(8)) => RustType::U64,
            (Some(DW_ATE_unsigned), Some(16)) => RustType::U128,

            // Floating point types
            (Some(DW_ATE_float), Some(4)) => RustType::F32,
            (Some(DW_ATE_float), Some(8)) => RustType::F64,

            (Some(DW_ATE_boolean), Some(1)) => RustType::Bool,

            // Unrecognized encoding/size combinations
            _ => return Err(ParsingError::Failure(
                format!(
                    "Encoding {:?} doesn't match byte site {:?}, type at offfset {:?} (hex {:x})",
                    encoding,
                    byte_size,
                    entry.offset().0,
                    entry.offset().0
                )
                .to_string(),
            )),
        };

        Ok(res)
    }

    fn parse_struct_type(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        node: gimli::EntriesTreeNode<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let name = node.entry().try_get_name(unit_ref)?;
        let byte_size = node.entry().try_get_byte_size()?;

        let mut fields = Vec::new();
        let mut children = node.children();

        while let Some(child) = children.next().unwrap() {
            //Handling Option type
            if child.entry().tag() == DW_TAG_variant_part {
                assert!(fields.is_empty());
                return Ok(self.parse_variant_part(unit_ref, byte_size, child)?);
            }
            if child.entry().tag() == DW_TAG_member {
                let field_name = child.entry().try_get_name(unit_ref)?;

                let field_type_id = self.resolve_type_id(child.entry(), unit_ref)?;
                let offset = child.entry().try_get_data_member_location()?;

                fields.push(StructField {
                    name: field_name,
                    field_type_id: field_type_id,
                    offset,
                });
            }
        }

        Ok(RustType::Struct {
            name,
            fields,
            size: byte_size,
            is_tuple_struct: false, // Would need heuristics to determine this
        })
    }

    fn parse_variant(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        node: gimli::EntriesTreeNode<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<Variant, ParsingError> {
        let entry = node.entry();
        let offset = entry.offset();

        assert!(entry.tag() == DW_TAG_variant);

        let discr_value = entry.try_get_as_u64(DW_AT_discr_value)?;
        let mut children = node.children();
        let mut variant = None;
        while let Some(child) = children.next().unwrap() {
            let child_entry = child.entry();
            if child_entry.tag() == DW_TAG_member {
                let variant_type_id = self.resolve_type_id(child_entry, unit_ref)?;
                assert!(variant == None);
                variant = Some(Variant {
                    discr_name: child_entry.try_get_name(unit_ref)?,
                    discr_value: discr_value,
                    rust_type_id: variant_type_id,
                    offset: child_entry.try_get_data_member_location()?,
                })
            }
        }
        if variant.is_some() {
            Ok(variant.unwrap())
        } else {
            Err(error_from_offset("Unable to extract variant type", &offset))
        }
    }
    //Parses logic for variant part i.e. rust Option and Result type.
    fn parse_variant_part(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        bytes_size: u64,
        node: gimli::EntriesTreeNode<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let entry = node.entry();
        assert!(entry.tag() == DW_TAG_variant_part);
        let offset = entry.offset();

        let attr_value = entry
            .attr_value(DW_AT_discr)?
            .ok_or_else(|| error_from_offset("Failed to find DW_AT_discr value", &offset))?;

        let AttributeValue::UnitRef(v) = attr_value else {
            return Err(error_from_offset("DW_AT_discr is not a UnitRef", &offset));
        };

        let discr_entry = unit_ref.entry(v).unwrap();
        let discriminant = Discriminant {
            rust_type_id: self.resolve_type_id(&discr_entry, unit_ref)?,
            offset: discr_entry.try_get_data_member_location()?,
        };

        let mut variants = Vec::new();
        let mut children = node.children();
        while let Some(child) = children.next().unwrap() {
            if child.entry().tag() == DW_TAG_variant {
                variants.push(self.parse_variant(unit_ref, child)?);
            }
        }

        if !variants.is_empty() {
            return Ok(RustType::VariantPart {
                discriminant: discriminant,
                variants: variants,
                size: bytes_size,
            });
        }

        Err(error_from_offset(
            "Could not parse an variant type",
            &offset,
        ))
    }

    fn parse_array_type(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let element_type = self.resolve_type_id(entry, unit_ref);

        let mut tree = unit_ref.entries_tree(Some(entry.offset())).unwrap();
        let node = tree.root().unwrap();
        // Look for subrange to get array size
        let mut element_count = 0;
        let mut children = node.children();
        while let Some(child) = children.next().unwrap() {
            if child.entry().tag() == DW_TAG_subrange_type {
                let mut attrs = child.entry().attrs();
                while let Some(attr) = attrs.next().unwrap() {
                    if attr.name() == DW_AT_count {
                        match attr.value() {
                            AttributeValue::Data1(count) => {
                                element_count = count as u64;
                            }
                            AttributeValue::Data2(count) => {
                                element_count = count as u64;
                            }
                            AttributeValue::Data4(count) => {
                                element_count = count as u64;
                            }
                            AttributeValue::Data8(count) => {
                                element_count = count;
                            }
                            _ => {}
                        }
                    }
                }
            }
        }

        Ok(RustType::Array {
            element_type_id: Box::new(element_type.unwrap()),
            element_count,
        })
    }

    fn parse_pointer_type(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let referent_type_id = self.resolve_type_id(entry, unit_ref);
        Ok(RustType::Reference {
            referent_id: Box::new(referent_type_id.unwrap()),
        })
    }

    fn parse_enum_type(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        node: gimli::EntriesTreeNode<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustType, ParsingError> {
        let name = node.entry().try_get_name(unit_ref)?;

        let mut variants = Vec::new();
        let mut children = node.children();
        while let Some(child) = children.next().unwrap() {
            if child.entry().tag() == DW_TAG_enumerator {
                let variant_name = child.entry().try_get_name(unit_ref)?;
                variants.push(EnumVariant {
                    name: variant_name,
                    variant_type: EnumVariantType::Unit,
                });
            }
        }

        Ok(RustType::Enum { name, variants })
    }

    pub fn process(
        &mut self,
        compilation_unit_vec: &[CompilationUnit<'_>],
    ) -> Result<(), ParsingError> {
        //Seed the worklist with all type_id from previously computed variable.
        self.worklist
            .add_all(extract_all_var_type_ids(compilation_unit_vec));

        // Solve the worklist
        while let Some(node) = self.worklist.pop() {
            let header = self
                .dwarf
                .debug_info
                .header_from_offset(DebugInfoOffset(node.1))
                .unwrap();
            let unit = &self.dwarf.unit(header).unwrap();
            let unit_ref = UnitRef::new(self.dwarf, unit);
            let parse_type = self.parse_variable_type_at_offset(&unit_ref, UnitOffset(node.0));
            if parse_type.is_ok() {
                assert!(!self.type_nodes.contains_key(&node), "Key already exists");
                self.type_nodes.insert(node, parse_type.unwrap());
            } else {
                self.parsing_errors
                    .push(format!("Type Parsing Error: {}", parse_type.unwrap_err()));
            }
        }
        Ok(())
    }

    fn add_to_worklist(&mut self, type_id: RustTypeId) {
        self.worklist.add(type_id);
    }

    fn resolve_type_id(
        &mut self,
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>, usize>,
        unit_ref: &UnitRef<'_, EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustTypeId, ParsingError> {
        let type_id = entry.try_get_type_id(unit_ref)?;
        self.add_to_worklist(type_id);
        Ok(type_id)
    }
}
