use std::{collections::{HashMap, HashSet}, path::PathBuf};

use serde::{Deserialize, Serialize};

use crate::{
    ParsingOptions,
    types::{
        operation_types::OperationJson,
        rust_type::{RustType, RustTypeId},
    },
};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct AddressRange {
    /**
     * The start address
     */
    pub start: u64,

    /**
     * The end address
     */
    pub end: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Eq, PartialEq)]
pub struct SourceRange {
    /**
     * The file path for the range
     */
    pub file_path: PathBuf,
    /**
     * The 0-based line number for the range
     */
    pub line: u64,
    /**
     * An optional column number for the range.
     */
    pub col: Option<u64>,
}

#[derive(PartialEq, Eq)]
pub enum MethodType {
    SubprogramType {
        /**
         * Contains the set of method that have been inlined into this subprogram.
         */
        inlined_methods: Vec<Method>,
        /**
         * The DWARF attribute DW_AT_linkage_name
         */
        linkage_name: String,
    },
    InlinedMethodType {
        /**
         * Encodes the source line information of the call site at which the method was inlined at.
         */
        call_site_range: SourceRange,
        /**
         * The depth of the inlining, if a subprogram foo contains a call to bar and bar contains a call to
         * baz and bar and baz have both been inlined, then the inline depth of baz will be strictly larger
         * than the inline depth of bar.
         */
        inline_depth: u64,
    },
}

#[derive(PartialEq, Eq)]
pub struct Method {
    pub method_type: MethodType,
    /**
     * The plain (demangeld) method name.
     */
    pub method_name: String,
    /**
     * The local variables and formal parameters of this current method.
     */
    pub variables: HashSet<Variable>,
    /**
     * The declaration range of the method, this will be (the start) line number information of the
     * method body. Note, `MethodType::InlinedMethodType` also has a call site range, this reflects the call site
     * and not the body of the function.
     */
    pub decl_range: SourceRange,
    /**
     * A list of ranges for which this information is valid for.
     */
    pub address_ranges: Vec<AddressRange>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Subprogram {
    pub method_name: String,
    pub linkage_name: String,
    pub variables: HashSet<Variable>,
    pub decl_range: SourceRange,
    pub address_ranges: Vec<AddressRange>,
    pub inlined_methods: Vec<InlinedMethod>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct InlinedMethod {
    pub inline_depth: u64,
    pub call_site_range: SourceRange,
    pub method_name: String,
    pub variables: HashSet<Variable>,
    pub decl_range: SourceRange,
    pub address_ranges: Vec<AddressRange>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Hash, PartialEq, Eq)]
pub struct Variable {
    /**
     * The bytecode address range where this variable information is valid for.
     */
    pub address_ranges: Vec<AddressRange>,

    #[serde(default)]
    /**
     * encode the list of debug information in which register etc the variable is currently maintained.
     */
    pub register_locations: Vec<DWARFOperationsList>,

    /**
     * The name of the variable
     */
    pub var_name: String,
    /**
     * The actual type of the variable
     */
    pub var_type_id: RustTypeId,
}

#[derive(Debug, Serialize, Deserialize, Clone, Eq, PartialEq, Hash)]
pub struct DWARFOperationsList {
    pub operations: Vec<OperationJson>,
    pub range: AddressRange,
}

#[derive(Serialize)]
pub struct CompilationUnit<'a> {
    /**
     * The options are passed around using this field.
     */
    #[serde(skip_serializing)]
    pub options: &'a ParsingOptions,

    /**
     * This field is only a data holder. Before deserialization, the methods
     * will be translated into [`Subprogram`] and be maintained in the `subprograms`
     * field.
     */
    #[serde(skip_serializing)]
    pub methods: Vec<Method>,

    pub subprograms: Vec<Subprogram>,
    /**
     * Contains the range of the compilation unit.
     */
    pub ranges: Vec<AddressRange>,

    /**
     * Contains "plain" line number information (information that is not part of the DWARF tree of Subprograms
     * and InlinedMethod, that also have decl_range and call_site_range information).
     */
    pub line_number_info: Vec<LineNumberInfo>,
    /**
     * (for debugging purposes) Contains the parsing errors when reading DWARF information.
     * This will be passed to the Kotlin-side for printling
     */
    pub parsing_errors: Vec<String>,
}

#[derive(Serialize, Clone)]
pub struct LineNumberInfo {
    pub address: u64,
    pub file_path: String,
    pub col: u64,
    pub line: u64,
}

#[derive(Serialize)]
pub struct DWARFDebugInformation<'a> {
    pub compilation_units: &'a Vec<CompilationUnit<'a>>,
    pub type_nodes: &'a HashMap<RustTypeId, RustType>,
    pub parsing_errors: Vec<String>,
}

/// Extract all var_type_id values from a vector of CompilationUnits
pub fn extract_all_var_type_ids<'a>(
    compilation_units: &'a [CompilationUnit],
) -> impl Iterator<Item = RustTypeId> + 'a {
    compilation_units.into_iter().flat_map(|cu| {
        cu.subprograms.iter().flat_map(|subprogram| {
            subprogram
                .variables
                .iter()
                .map(|x| x.var_type_id.clone())
                .chain(
                    subprogram.inlined_methods.iter().flat_map(|inlined| {
                        inlined.variables.iter().map(|x| x.var_type_id.clone())
                    }),
                )
        })
    })
}
