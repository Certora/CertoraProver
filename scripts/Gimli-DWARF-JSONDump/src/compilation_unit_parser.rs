use std::collections::HashSet;
// DWARF Compilation Unit Parser
//
// This module handles the parsing of DWARF compilation units, which represent
// individual source files or translation units in the compiled program.
// Each compilation unit contains debugging information for variables, functions,
// types, and source line mappings for that unit.
use std::path::{self};

use crate::die_extensions::{DebugEntryExt, ParsingError};
use crate::types::data_structures::{
    AddressRange, CompilationUnit, InlinedMethod, LineNumberInfo, Method, MethodType, Subprogram,
    Variable,
};
use gimli::{
    AttributeValue, DW_AT_abstract_origin, DW_TAG_formal_parameter, DW_TAG_inlined_subroutine,
    DW_TAG_subprogram, DW_TAG_variable, DebuggingInformationEntry, UnitRef,
};
use gimli::{EndianSlice, RunTimeEndian};
use rustc_demangle::demangle;
impl<'a> CompilationUnit<'a> {
    /// Parse a complete compilation unit from DWARF information
    ///
    /// This is the main entry point for processing a compilation unit. It extracts
    /// both the hierarchical debugging information (DIEs) and the line number program
    /// that maps addresses to source code locations.
    ///
    /// # Arguments
    /// * `header` - The DWARF unit header containing compilation unit metadata
    ///
    /// # Returns
    /// * `Ok(CompilationUnit)` - Successfully parsed compilation unit
    /// * `Err(gimli::Error)` - Parsing failed due to malformed DWARF data
    pub fn parse_compilation_unit(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<(), ParsingError> {
        // Parse the line number program for address-to-source mapping
        self.parse_line_number_program(unit_ref)?;

        // Parse the hierarchical tree of debugging information entries
        self.traverse_and_flatten(unit_ref)?;
        self.translate_methods();
        Ok(())
    }

    /// Parse the line number program for a compilation unit
    ///
    /// The line number program maps machine code addresses back to source file
    /// locations (file, line, column). This is essential for debuggers to show
    /// the correct source code when stepping through execution.
    ///
    /// # Arguments
    /// * `header` - The compilation unit header
    ///
    /// # Returns
    /// Vector of line number entries mapping addresses to source locations
    fn parse_line_number_program(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<(), gimli::Error> {
        let mut line_info = Vec::new();

        // Get the line program for the compilation unit.
        if let Some(program) = unit_ref.line_program.clone() {
            // Establish the compilation directory as the base for relative paths
            let comp_dir = if let Some(ref dir) = unit_ref.comp_dir {
                path::PathBuf::from(dir.to_string_lossy().into_owned())
            } else {
                path::PathBuf::new()
            };

            // Iterate over the line program rows to build address-to-source mappings
            let mut rows = program.rows();
            while let Some((header, row)) = rows.next_row()? {
                if row.end_sequence() {
                    // End of sequence indicates a possible gap in addresses.
                    // This marks the end of a contiguous sequence of instructions.
                    line_info.push(LineNumberInfo {
                        address: row.address(),
                        file_path: "end-sequence".to_owned(),
                        line: 0,
                        col: 0,
                    })
                } else {
                    // Determine the path.
                    let mut path = path::PathBuf::new();
                    if let Some(file) = row.file(header) {
                        path.clone_from(&comp_dir);

                        // The directory index 0 is defined to correspond to the compilation unit directory.
                        if file.directory_index() != 0 {
                            if let Some(dir) = file.directory(header) {
                                path.push(unit_ref.attr_string(dir)?.to_string_lossy().as_ref());
                            }
                        }

                        path.push(
                            unit_ref
                                .attr_string(file.path_name())?
                                .to_string_lossy()
                                .as_ref(),
                        );
                    }

                    // Determine line/column. DWARF line/column is never 0, so we use that
                    // but other applications may want to display this differently.
                    let line = match row.line() {
                        Some(line) => line.get(),
                        None => 0,
                    };
                    let column = match row.column() {
                        gimli::ColumnType::LeftEdge => 0,
                        gimli::ColumnType::Column(column) => column.get(),
                    };

                    line_info.push(LineNumberInfo {
                        address: row.address(),
                        file_path: path.display().to_string(),
                        line: line,
                        col: column,
                    })
                }
            }
        }
        self.line_number_info = line_info;

        Ok(())
    }

    /// Takes the root node of the DWARF tree and starts the traversal.
    fn traverse_and_flatten(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<(), ParsingError> {
        let mut tree = unit_ref.entries_tree(None)?;
        let root = tree.root()?;
        self.ranges = root
            .entry()
            .try_get_address_ranges(unit_ref)
            .unwrap_or(Vec::new());
        self.traverse_and_flatten_rec(
            unit_ref,
            root,
            &self.ranges.clone(),
            0,
            &mut None,
            &mut None,
        )
    }

    /// Traverses over the entire DWARF tree and
    /// 1. For DW_TAG_subprogram generates a new  [`Method`] (of [`MethodType::SubprogramType`])
    ///    that is associates to the [`CompilationUnit`] (self),
    ///    and traverses recursively into [`CompilationUnit::traverse_and_flatten_rec`] with the newly created [`Method`].
    /// 2. For DW_TAG_inlined_subroutine generates new  [`Method`] (of [`MethodType::InlinedMethodType`])
    ///    that is associated to top_subprogram, which is the most recently visited [`Method`]
    ///    (of [`MethodType::SubprogramType`]). Traverses recursively into [`CompilationUnit::traverse_and_flatten_rec`]
    ///    using the newly crated [`Method`]
    /// 3. For DW_TAG_variable | DW_TAG_formal_parameter constructs the [`Variable`] and associates it
    ///    to the most recent seen inlined method, i.e. (last_seen_inlined_method), if present. If there is no
    ///    last seen inlined method present, then the variable belongs to the `last_seen_subprogram`.
    /// 4. For all other nodes, keep traversing into the tree.
    ///
    /// Note, some special handling is added for the entry attribute DW_AT_abstract_origin. If it's present
    /// recurses into this entry as well - variable information can be stored there too.
    fn traverse_and_flatten_rec(
        &mut self,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        curr: gimli::EntriesTreeNode<EndianSlice<'a, RunTimeEndian>>,
        // the last ranges (transitively) that any of the parent node had
        last_seen_parent_ranges: &Vec<AddressRange>,
        depth: u64,
        //Holds a reference to the top most subprogram that was last seen
        last_seen_subprogram: &mut Option<&mut Method>,
        //Holds a reference to last inlined method seen
        last_seen_inlined_method: &mut Option<&mut Method>,
    ) -> Result<(), ParsingError> {
        //If this node has a DW_AT_abstract origin, recurse into it.
        if let Ok(Some(attr)) = curr.entry().attr(DW_AT_abstract_origin) {
            let attr_value = attr.value();
            match attr_value {
                AttributeValue::UnitRef(v) => self.traverse_and_flatten_rec(
                    unit_ref,
                    unit_ref.entries_tree(Some(v)).unwrap().root().unwrap(),
                    last_seen_parent_ranges,
                    depth,
                    last_seen_subprogram,
                    last_seen_inlined_method,
                )?,
                _ => {}
            }
        }

        let mut children = curr.children();
        let next_depth = depth + 1;

        while let Some(child) = children.next()? {
            let entry = child.entry();
            let child_ranges =
                get_address_ranges_or_take_parent(entry, unit_ref, last_seen_parent_ranges);
            match entry.tag() {
                DW_TAG_subprogram => {
                    let res_subprogram = Method::create(entry, unit_ref, depth);
                    if res_subprogram.is_ok() {
                        let mut subprogram = res_subprogram.unwrap();
                        self.traverse_and_flatten_rec(
                            unit_ref,
                            child,
                            &child_ranges,
                            next_depth,
                            &mut Some(&mut subprogram),
                            &mut None,
                        )?;
                        self.methods.push(subprogram);
                    } else {
                        // We ignore subprograms without ranges, as the information on these
                        // nodes will be extracted via DW_AT_abstract_origin and DW_AT_specification lookups
                        // on the inlined methods.
                    }
                }
                DW_TAG_inlined_subroutine => {
                    let res_inlined_method = Method::create(entry, unit_ref, depth);
                    if res_inlined_method.is_ok() {
                        let mut inlined_method = res_inlined_method.unwrap();
                        self.traverse_and_flatten_rec(
                            unit_ref,
                            child,
                            &child_ranges,
                            next_depth,
                            last_seen_subprogram,
                            &mut Some(&mut inlined_method),
                        )?;
                        let method_type = &mut last_seen_subprogram.as_mut().unwrap().method_type;
                        match method_type {
                            MethodType::InlinedMethodType {
                                call_site_range: _,
                                inline_depth: _,
                            } => panic!(
                                "Encountered an inlined method, but the top level element should always be a subprogram."
                            ),
                            MethodType::SubprogramType {
                                inlined_methods,
                                linkage_name: _,
                            } => {
                                inlined_methods.push(inlined_method);
                            }
                        }
                    }
                }
                DW_TAG_variable | DW_TAG_formal_parameter => {
                    if !self.options.extract_variables {
                        continue;
                    }

                    let variable = Variable::create(entry, &child_ranges, unit_ref);

                    if variable.is_ok() {
                        let res = variable.unwrap();
                        if last_seen_inlined_method.is_some() {
                            last_seen_inlined_method
                                .as_mut()
                                .unwrap()
                                .variables
                                .insert(res);
                        } else if last_seen_subprogram.is_some() {
                            last_seen_subprogram.as_mut().unwrap().variables.insert(res);
                        }
                    } else {
                        let method_name = if last_seen_inlined_method.is_some() {
                            &last_seen_inlined_method.as_mut().unwrap().method_name
                        } else if last_seen_subprogram.is_some() {
                            &last_seen_subprogram.as_mut().unwrap().method_name
                        } else {
                            &"".to_string()
                        };
                        let error_msg = format!(
                            "Failed to parse variable {:?} in inlined method. {:?}, {:?}",
                            entry
                                .try_get_name(unit_ref)
                                .unwrap_or("<UNKNOWN>".to_string()),
                            method_name,
                            variable.err()
                        );
                        self.parsing_errors.push(error_msg);
                    }
                }
                _ => {
                    self.traverse_and_flatten_rec(
                        unit_ref,
                        child,
                        &child_ranges,
                        next_depth,
                        last_seen_subprogram,
                        last_seen_inlined_method,
                    )?;
                }
            }
        }
        Ok(())
    }

    /// Translates the data structures to the serialization version
    fn translate_methods(&mut self) {
        for method in &self.methods {
            let method_type = &method.method_type;
            match method_type {
                MethodType::InlinedMethodType {
                    call_site_range: _,
                    inline_depth: _,
                } => panic!(
                    "Encountered an inlined methods as top level, expecting to see only Subprograms here. Inlined methods should always be below a subprogram"
                ),
                MethodType::SubprogramType {
                    inlined_methods,
                    linkage_name,
                } => {
                    let mut subprogram = Subprogram {
                        method_name: method.method_name.clone(),
                        variables: method.variables.clone(),
                        decl_range: method.decl_range.clone(),
                        address_ranges: method.address_ranges.clone(),
                        linkage_name: linkage_name.clone(),
                        inlined_methods: Vec::new(),
                    };
                    for inlined_method in inlined_methods {
                        let inlined_method_type = &inlined_method.method_type;
                        match inlined_method_type {
                            MethodType::SubprogramType {
                                inlined_methods: _,
                                linkage_name: _,
                            } => panic!(
                                "Encountered a subprogram below a subprogram, but only expecting to see an inlined method below a subprogram"
                            ),
                            MethodType::InlinedMethodType {
                                call_site_range,
                                inline_depth,
                            } => {
                                let inlined = InlinedMethod {
                                    method_name: inlined_method.method_name.clone(),
                                    variables: inlined_method.variables.clone(),
                                    decl_range: inlined_method.decl_range.clone(),
                                    address_ranges: inlined_method.address_ranges.clone(),
                                    inline_depth: inline_depth.clone(),
                                    call_site_range: call_site_range.clone(),
                                };
                                subprogram.inlined_methods.push(inlined);
                            }
                        }
                    }
                    self.subprograms.push(subprogram);
                }
            }
        }
    }
}

trait FromNode {
    fn create<'a>(
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>, usize>,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        depth: u64,
    ) -> Result<Self, ParsingError>
    where
        Self: Sized;
}

impl Variable {
    fn create<'a>(
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>, usize>,
        address_ranges: &Vec<AddressRange>,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    ) -> Result<Self, ParsingError> {
        let variable_name = entry.try_get_name(unit_ref)?;
        let variable_register_locations =
            entry.try_get_variable_register(unit_ref, &address_ranges)?;
        let variable_type = entry.try_get_type_id(unit_ref)?;
        Ok(Variable {
            // Only if the variable register location information is empty do we pass address ranges down.
            // to avoid duplicate information
            address_ranges: if variable_register_locations.is_empty() {
                address_ranges.to_vec()
            } else {
                Vec::new()
            },
            register_locations: variable_register_locations,
            var_name: variable_name,
            var_type_id: variable_type,
        })
    }
}

impl FromNode for Method {
    fn create<'a>(
        entry: &DebuggingInformationEntry<'_, '_, EndianSlice<'a, RunTimeEndian>, usize>,
        unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
        depth: u64,
    ) -> Result<Self, ParsingError>
    where
        Self: Sized,
    {
        let mangled_method_name = entry.try_get_name(unit_ref)?;
        let address_ranges = entry.try_get_address_ranges(unit_ref)?;

        let method_type = if entry.tag() == DW_TAG_subprogram {
            let linkage_name = entry
                .try_get_linkage_name(unit_ref)
                .unwrap_or("<UNKNOWN>".to_string());
            MethodType::SubprogramType {
                inlined_methods: Vec::new(),
                linkage_name: linkage_name,
            }
        } else if entry.tag() == DW_TAG_inlined_subroutine {
            let call_site_range = entry.try_get_call_site_range(unit_ref)?;
            MethodType::InlinedMethodType {
                call_site_range: call_site_range,
                inline_depth: depth,
            }
        } else {
            panic!("Unexected tag type {:?}", entry.tag().static_string())
        };
        Ok(Method {
            method_type: method_type,
            method_name: format!("{}", demangle(&mangled_method_name)),
            variables: HashSet::new(),
            decl_range: entry.try_get_decl_range(unit_ref)?,
            address_ranges: address_ranges,
        })
    }
}

/// Tries to get the range of the current [entry], if this fails or the range is empty take the supplied [parent_ranges].
fn get_address_ranges_or_take_parent<'a>(
    entry: &DebuggingInformationEntry<EndianSlice<'a, RunTimeEndian>>,
    unit_ref: &UnitRef<EndianSlice<'a, RunTimeEndian>>,
    parent_ranges: &Vec<AddressRange>,
) -> Vec<AddressRange> {
    let address_ranges = entry.try_get_address_ranges(unit_ref).unwrap_or(Vec::new());
    if address_ranges.is_empty() {
        parent_ranges.clone()
    } else {
        address_ranges
    }
}
