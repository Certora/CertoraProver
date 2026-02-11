use core::fmt;
use std::path::PathBuf;

use gimli::{
    AttributeValue, DW_AT_abstract_origin, DW_AT_byte_size, DW_AT_call_column, DW_AT_call_file,
    DW_AT_call_line, DW_AT_const_value, DW_AT_data_member_location, DW_AT_decl_column,
    DW_AT_decl_file, DW_AT_decl_line, DW_AT_discr_value, DW_AT_high_pc, DW_AT_linkage_name,
    DW_AT_location, DW_AT_low_pc, DW_AT_name, DW_AT_ranges, DW_AT_specification, DW_AT_type,
    DebuggingInformationEntry, EndianSlice, OperationIter, RangeListsOffset, RunTimeEndian,
    UnitOffset, UnitRef,
};

use crate::types::{
    data_structures::{AddressRange, DWARFOperationsList, SourceRange},
    operation_types::OperationJson,
    rust_type::RustTypeId,
};

// Simple custom error
#[derive(Debug)]
pub enum ParsingError {
    Failure(String),
}

impl fmt::Display for ParsingError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            ParsingError::Failure(msg) => write!(f, "Parsing failed: {}", msg),
        }
    }
}

impl From<gimli::Error> for ParsingError {
    fn from(error: gimli::Error) -> Self {
        ParsingError::Failure(error.description().to_string())
    }
}
pub trait DebugEntryExt<'abbrev, 'unit, 'a, 'c> {
    fn try_get_name(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<String, ParsingError>;
    fn try_get_linkage_name(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<String, ParsingError>;
    fn try_get_decl_range(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<SourceRange, ParsingError>;
    fn try_get_call_site_range(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<SourceRange, ParsingError>;
    fn try_get_address_ranges(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<Vec<AddressRange>, ParsingError>;

    fn try_get_variable_register(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        // A default address range to take in case no other is present, comes from the last parent.
        default_address_range: &Vec<AddressRange>,
    ) -> Result<Vec<DWARFOperationsList>, ParsingError>;

    fn try_get_data_member_location(&self) -> Result<u64, ParsingError>;

    fn try_get_byte_size(&self) -> Result<u64, ParsingError>;

    fn try_get_type_id(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustTypeId, ParsingError>;

    fn try_get_as_u64(&self, u64_attr: gimli::constants::DwAt) -> Result<u64, ParsingError>;
}

impl<'abbrev, 'unit, 'a, 'c: 'a> DebugEntryExt<'abbrev, 'unit, 'a, 'c>
    for DebuggingInformationEntry<'abbrev, 'unit, EndianSlice<'a, RunTimeEndian>>
{
    fn try_get_name(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<String, ParsingError> {
        match self.attr(DW_AT_name) {
            Ok(Some(attr)) => {
                if let Ok(s) = unit_ref.attr_string(attr.value()) {
                    let t = s.to_string_lossy();
                    return Ok(format!("{}", t));
                }

                match attr.value() {
                    AttributeValue::DebugStrRef(r) => {
                        if let Ok(s) = unit_ref.dwarf.string(r) {
                            let t = s.to_string_lossy();
                            return Ok(format!("{}", t));
                        }
                    }
                    _ => (),
                }
            }
            _ => (),
        }

        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_name(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_name(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not find DW_AT_name", self))
    }

    fn try_get_linkage_name(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<String, ParsingError> {
        match self.attr(DW_AT_linkage_name) {
            Ok(Some(attr)) => {
                if let Ok(s) = unit_ref.attr_string(attr.value()) {
                    let t = s.to_string_lossy();
                    return Ok(format!("{}", t));
                }

                match attr.value() {
                    AttributeValue::DebugStrRef(r) => {
                        if let Ok(s) = unit_ref.dwarf.string(r) {
                            let t = s.to_string_lossy();
                            return Ok(format!("{}", t));
                        }
                    }
                    _ => (),
                }
            }
            _ => (),
        }

        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_linkage_name(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_linkage_name(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not find DW_AT_linkage_name", self))
    }

    fn try_get_decl_range(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<SourceRange, ParsingError> {
        let decl_file = try_get_as_file_name(self, unit_ref, DW_AT_decl_file);
        let decl_line = self.try_get_as_u64(DW_AT_decl_line);
        let decl_col = self.try_get_as_u64(DW_AT_decl_column);

        if let (Some(file), Ok(line)) = (decl_file, decl_line) {
            if line == 0 {
                return Err(error(
                    "The line numbers are 1-based, did not expect to find a 0 for a declaration range",
                    self,
                ));
            }

            return Ok(SourceRange {
                file_path: file,
                line,
                col: decl_col.ok(),
            });
        }

        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_decl_range(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_decl_range(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not find decl range", self))
    }

    fn try_get_call_site_range(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<SourceRange, ParsingError> {
        let call_file = try_get_as_file_name(self, unit_ref, DW_AT_call_file);
        let call_line = self.try_get_as_u64(DW_AT_call_line);
        let call_col = self.try_get_as_u64(DW_AT_call_column);

        if let (Some(call_file), Ok(call_line)) = (call_file, call_line) {
            if call_line == 0 {
                return Err(error(
                    "The line numbers are 1-based, did not expect to find a 0 for a call site range",
                    self,
                ));
            }
            return Ok(SourceRange {
                file_path: call_file,
                line: call_line,
                col: call_col.ok(),
            });
        }

        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_call_site_range(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_call_site_range(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not get call site range", self))
    }

    fn try_get_address_ranges(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<Vec<AddressRange>, ParsingError> {
        let mut ranges = Vec::new();
        let mut low_pc = None;
        let mut high_pc = None;

        match self.attr(DW_AT_ranges) {
            Ok(Some(attr)) => {
                match attr.value() {
                    AttributeValue::RangeListsRef(offset) => {
                        let mut loc_list = unit_ref.ranges(RangeListsOffset(offset.0))?;
                        // Iterate through location entries
                        while let Some(loc_entry) = loc_list.next()? {
                            ranges.push(AddressRange {
                                start: loc_entry.begin,
                                end: loc_entry.end,
                            })
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }

        match self.attr(DW_AT_low_pc) {
            Ok(Some(attr)) => {
                if let Ok(s) = unit_ref.attr_address(attr.value()) {
                    low_pc = s;
                }
            }
            _ => {}
        }

        match self.attr(DW_AT_high_pc) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::Udata(val) => high_pc = low_pc.map(|size| size + val),
                attr => high_pc = unit_ref.attr_address(attr)?,
            },
            _ => {}
        }

        if low_pc.is_some() && high_pc.is_some() {
            ranges.push(AddressRange {
                start: low_pc.unwrap(),
                end: high_pc.unwrap(),
            });
        }
        let non_zero_ranges: Vec<AddressRange> = ranges
            .into_iter()
            .filter(|el| el.end != 0 && el.start != 0)
            .collect();
        if !non_zero_ranges.is_empty() {
            return Ok(non_zero_ranges);
        }
        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_address_ranges(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_address_ranges(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not get ranges", self))
    }

    fn try_get_variable_register(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        default_address_ranges: &Vec<AddressRange>,
    ) -> Result<Vec<DWARFOperationsList>, ParsingError> {
        let mut res = Vec::new();
        match self.attr(DW_AT_location) {
            Ok(Some(attr)) => {
                match attr.value() {
                    AttributeValue::LocationListsRef(_) => {
                        let x = unit_ref.attr_locations(attr.value())?;
                        if let Some(mut loc_list) = x {
                            // Iterate through location entries for different address ranges
                            while let Some(loc_entry) = loc_list.next()? {
                                let iter = loc_entry.data.operations(unit_ref.encoding());
                                let ranges = create_ranges_from_operation_iter(iter);
                                if ranges.is_some() {
                                    let ops = ranges.unwrap();
                                    res.push(DWARFOperationsList {
                                        operations: ops,
                                        range: AddressRange {
                                            start: loc_entry.range.begin,
                                            end: loc_entry.range.end,
                                        },
                                    });
                                }
                            }
                        }
                    }
                    AttributeValue::Exprloc(x) => {
                        let iter = x.operations(unit_ref.encoding());
                        let ranges = create_ranges_from_operation_iter(iter);
                        if ranges.is_some() {
                            let ops = ranges.unwrap();
                            for addr_range in default_address_ranges {
                                res.push(DWARFOperationsList {
                                    operations: ops.clone(),
                                    range: addr_range.clone(),
                                });
                            }
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
        let const_value_res = self.try_get_as_u64(DW_AT_const_value);
        if const_value_res.is_ok() {
            let mut ops = Vec::new();
            ops.push(OperationJson::UnsignedConstant {
                value: const_value_res.unwrap(),
            });
            for addr_range in default_address_ranges {
                res.push(DWARFOperationsList {
                    operations: ops.clone(),
                    range: addr_range.clone(),
                });
            }
        }
        if !res.is_empty() {
            return Ok(res);
        }
        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res =
                            r.try_get_variable_register(unit_ref, default_address_ranges);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res =
                            r.try_get_variable_register(unit_ref, default_address_ranges);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Ok(res)
    }

    fn try_get_data_member_location(&self) -> Result<u64, ParsingError> {
        self.try_get_as_u64(DW_AT_data_member_location)
    }
    fn try_get_byte_size(&self) -> Result<u64, ParsingError> {
        self.try_get_as_u64(DW_AT_byte_size)
    }

    fn try_get_type_id(
        &self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<RustTypeId, ParsingError> {
        let attr_value = self.attr_value(DW_AT_type);
        // Search through the entry's attributes for type information
        match attr_value {
            Ok(Some(attr)) => {
                match attr {
                    // Follow the type reference to get the actual type definition
                    AttributeValue::UnitRef(type_offset) => {
                        return Ok(RustTypeId(
                            type_offset.0,
                            unit_ref.header.offset().as_debug_info_offset().unwrap().0,
                        ));
                    }
                    _ => {}
                }
            }
            _ => {}
        }

        // recurse into children // duplicated code
        match self.attr(DW_AT_specification) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_type_id(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        match self.attr(DW_AT_abstract_origin) {
            Ok(Some(attr)) => match attr.value() {
                AttributeValue::UnitRef(v) => {
                    if let Ok(r) = unit_ref.entry(v) {
                        let spec_res = r.try_get_type_id(unit_ref);
                        if spec_res.is_ok() {
                            return spec_res;
                        }
                    }
                }
                _ => (),
            },
            _ => {}
        }
        Err(error("Could not not parse DW_AT_type", self))
    }

    fn try_get_as_u64(&self, u64_attr: gimli::constants::DwAt) -> Result<u64, ParsingError> {
        assert!(
            u64_attr == DW_AT_decl_column
                || u64_attr == DW_AT_decl_line
                || u64_attr == DW_AT_call_column
                || u64_attr == DW_AT_call_line
                || u64_attr == DW_AT_byte_size
                || u64_attr == DW_AT_data_member_location
                || u64_attr == DW_AT_discr_value
                || u64_attr == DW_AT_const_value
        );
        let attr = self.attr(u64_attr);
        match attr {
            Ok(Some(attr_content)) => match attr_content.value() {
                AttributeValue::Udata(val) => return Ok(val),
                AttributeValue::Data1(val) => return Ok(val as u64),
                AttributeValue::Data2(val) => return Ok(val as u64),
                AttributeValue::Data4(val) => return Ok(val as u64),
                AttributeValue::Data8(val) => return Ok(val as u64),
                _ => {}
            },
            _ => {}
        }
        Err(error(
            &format!("Could not parse attr {} as u64", u64_attr),
            self,
        ))
    }
}

pub fn error<'abbrev, 'unit, 'a>(
    msg: &str,
    die: &DebuggingInformationEntry<'abbrev, 'unit, EndianSlice<'a, RunTimeEndian>>,
) -> ParsingError {
    return error_from_offset(msg, &die.offset());
}

pub fn error_from_offset<'abbrev, 'unit, 'a>(msg: &str, unit_offset: &UnitOffset) -> ParsingError {
    let offset = unit_offset.0;
    let error_msg = format!(
        "{} [DebuggingInformationEntry at offset {:?} (hex: {:x})]",
        msg, offset, offset
    );
    ParsingError::Failure(error_msg)
}

fn try_get_as_file_name<'abbrev, 'unit, 'a>(
    die: &DebuggingInformationEntry<'abbrev, 'unit, EndianSlice<'a, RunTimeEndian>>,
    unit_ref: &UnitRef<EndianSlice<'_, RunTimeEndian>>,
    file_attr: gimli::constants::DwAt,
) -> Option<PathBuf> {
    assert!(file_attr == DW_AT_decl_file || file_attr == DW_AT_call_file);
    let attr_decl_file = die.attr(file_attr);
    match attr_decl_file {
        Ok(Some(attr_file)) => match attr_file.value() {
            AttributeValue::FileIndex(val) => return get_file_name(unit_ref, val),
            _ => (),
        },
        _ => {}
    }
    None
}

fn get_file_name(
    unit_ref: &UnitRef<EndianSlice<'_, RunTimeEndian>>,
    file_index: u64,
) -> Option<PathBuf> {
    if file_index == 0 && unit_ref.header.version() <= 4 {
        return None;
    }
    let header = match unit_ref.line_program {
        Some(ref program) => program.header(),
        None => return None,
    };
    let file = match header.file(file_index) {
        Some(file) => file,
        None => {
            return None;
        }
    };
    let mut path = PathBuf::new();
    if let Some(directory) = file.directory(header) {
        let directory = unit_ref.attr_string(directory).unwrap();
        let directory = directory.to_string_lossy();
        if file.directory_index() != 0 && !directory.starts_with('/') {
            if let Some(ref comp_dir) = unit_ref.comp_dir {
                path.push(comp_dir.to_string_lossy().as_ref());
            }
        }
        path.push(directory.as_ref());
    }
    path.push(
        &unit_ref
            .attr_string(file.path_name())
            .unwrap()
            .to_string_lossy().as_ref(),
    );

    Some(path)
}

fn create_ranges_from_operation_iter(
    mut iter: OperationIter<EndianSlice<'_, RunTimeEndian>>,
) -> Option<Vec<OperationJson>> {
    let mut operations = Vec::new();
    // Convert each DWARF operation to our JSON-serializable format
    while let Some(el) = iter.next().ok()? {
        let op = OperationJson::try_from(&el).ok()?;
        operations.push(op);
    }

    Some(operations)
}

impl<'abbrev, 'unit, 'a, 'c: 'a> dyn DebugEntryExt<'abbrev, 'unit, 'a, 'c> {}
