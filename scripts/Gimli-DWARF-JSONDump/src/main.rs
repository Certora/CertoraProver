// Gimli DWARF JSON Dump Tool
//
// This tool parses DWARF debugging information from WebAssembly (WASM) binary files
// and outputs the parsed data as JSON for further analysis and debugging purposes.
//
// The primary use case is to extract structured debugging information including:
// - Compilation unit information
// - Variable type information with Rust-specific type mapping
// - Location and range information for variables
// - Source file and line number mappings
#![allow(non_upper_case_globals)]

use object::{Object, ObjectSection};
use std::io;
use std::process;
use std::{
    borrow, env, error,
    fs::{self, File},
    io::{BufWriter, Write},
};

use crate::die_extensions::ParsingError;
use crate::type_parser::TypeParser;
use crate::types::data_structures::CompilationUnit;
use crate::types::data_structures::DWARFDebugInformation;

// Module declarations for the different parsing components
mod compilation_unit_parser; // Main compilation unit parsing logic
mod die_extensions;
mod type_parser;
mod types; // DWARF operation type definitions and JSON serialization
mod worklist;
/// Wrapper around object::read::RelocationMap to implement gimli's Relocate trait
///
/// This structure is needed to handle relocation of DWARF addresses and offsets
/// when reading from object files that may have relocations applied.
#[derive(Debug, Default)]
struct RelocationMap(object::read::RelocationMap);

impl<'a> gimli::read::Relocate for &'a RelocationMap {
    /// Relocate an address value at the given offset
    ///
    /// # Arguments
    /// * `offset` - The offset in the section where relocation should be applied
    /// * `value` - The original address value to relocate
    ///
    /// # Returns
    /// The relocated address value
    fn relocate_address(&self, offset: usize, value: u64) -> gimli::Result<u64> {
        Ok(self.0.relocate(offset as u64, value))
    }

    /// Relocate an offset value at the given offset
    ///
    /// # Arguments
    /// * `offset` - The offset in the section where relocation should be applied
    /// * `value` - The original offset value to relocate
    ///
    /// # Returns
    /// The relocated offset value as a usize
    fn relocate_offset(&self, offset: usize, value: usize) -> gimli::Result<usize> {
        <usize as gimli::ReaderOffset>::from_u64(self.0.relocate(offset as u64, value as u64))
    }
}
/// Print usage information and exit the program
///
/// This function displays the command-line usage information to stderr
/// and terminates the program with exit code 1.
///
/// # Arguments
/// * `opts` - The getopts Options structure containing option definitions
fn print_usage(opts: &getopts::Options) -> ! {
    let brief = format!("Usage: {} <options> <file>", env::args().next().unwrap());
    write!(&mut io::stderr(), "{}", opts.usage(&brief)).ok();
    process::exit(1);
}
/// Main entry point for the DWARF JSON dump tool
///
/// This function handles command-line argument parsing, file loading,
/// and orchestrates the DWARF parsing and JSON output generation process.
fn main() {
    // Set up command-line option definitions
    let mut opts = getopts::Options::new();
    opts.reqopt(
        "i",
        "inputFile",
        "The (relative or absolute) WASM input file ",
        "example.wasm",
    );
    opts.optopt(
        "o",
        "outputFile",
        "The path (relative or absolute) JSON output file",
        "example-dwarf-output.json",
    );
    opts.optflag("d", "demangle", "Demangle names in the output");
    opts.optflag(
        "v",
        "variables",
        "Extract variable (and type) information from DWARF",
    );

    // Parse command-line arguments
    let matches = match opts.parse(env::args().skip(1)) {
        Ok(m) => m,
        Err(e) => {
            writeln!(&mut io::stderr(), "{:?}\n", e).ok();
            print_usage(&opts);
        }
    };

    // Extract the required input file path
    let input_file_path = if let Some(r) = matches.opt_str("i") {
        r
    } else {
        print_usage(&opts)
    };

    // Configure processing flags based on command-line options
    let mut options = ParsingOptions::default();
    if matches.opt_present("d") {
        options.demangle = true;
    } else {
        options.demangle = false;
    }
    if matches.opt_present("v") {
        options.extract_variables = true;
    } else {
        options.extract_variables = false;
    }

    options.output_file_path = if let Some(r) = matches.opt_str("o") {
        Some(r)
    } else {
        None
    };

    // Load and memory-map the input file for efficient reading
    let file = fs::File::open(input_file_path).unwrap();
    let mmap = unsafe { memmap2::Mmap::map(&file).unwrap() };
    let object = object::File::parse(&*mmap).unwrap();

    // Determine the endianness of the object file for proper DWARF parsing
    let endian = if object.is_little_endian() {
        gimli::RunTimeEndian::Little
    } else {
        gimli::RunTimeEndian::Big
    };

    // Generate the JSON dump of DWARF information
    generate_jsondump(&options, &object, endian).unwrap();
}

/// Generate a JSON dump of DWARF debugging information
///
/// This function loads DWARF sections from the object file, parses compilation units,
/// and outputs the structured information as JSON to either a file or stdout.
///
/// # Arguments
/// * `flags` - Configuration flags controlling output behavior
/// * `object` - The parsed object file containing DWARF sections
/// * `endian` - The endianness of the object file
///
/// # Returns
/// Result indicating success or failure with error details
fn generate_jsondump(
    parsing_options: &ParsingOptions,
    object: &object::File,
    endian: gimli::RunTimeEndian,
) -> Result<(), Box<ParsingError>> {
    // Load a section and return as `Cow<[u8]>`.
    // This closure handles loading DWARF sections from the object file,
    // returning empty sections if the requested section doesn't exist.
    let load_section = |id: gimli::SectionId| -> Result<borrow::Cow<[u8]>, Box<dyn error::Error>> {
        Ok(match object.section_by_name(id.name()) {
            Some(section) => section.uncompressed_data()?,
            None => borrow::Cow::Borrowed(&[]),
        })
    };

    // Borrow a `Cow<[u8]>` to create an `EndianSlice`.
    // This closure creates proper endian-aware slices for DWARF parsing.
    let borrow_section = |section| gimli::EndianSlice::new(borrow::Cow::as_ref(section), endian);

    // Load all DWARF sections (.debug_info, .debug_abbrev, etc.)
    let dwarf_sections = gimli::DwarfSections::load(&load_section).unwrap();

    // Create `EndianSlice`s for all of the sections for efficient parsing
    let dwarf = dwarf_sections.borrow(borrow_section);

    // Iterate over all compilation units in the DWARF data
    let mut iter = dwarf.units();
    let mut compilation_unit_vec = Vec::new();
    while let Some(header) = iter.next().unwrap() {
        let unit = dwarf.unit(header).unwrap();
        let unit_ref: gimli::UnitRef<'_, gimli::EndianSlice<'_, gimli::RunTimeEndian>> =
            unit.unit_ref(&dwarf);
        let mut cu_res = CompilationUnit {
            options: parsing_options,
            methods: Vec::new(),
            subprograms: Vec::new(),
            line_number_info: Vec::new(),
            ranges: Vec::new(),
            parsing_errors: Vec::new(),
        };
        cu_res.parse_compilation_unit(&unit_ref)?;

        // Parse each compilation unit and collect the results
        compilation_unit_vec.push(cu_res);
    }

    // Now parse all type information
    let mut type_parser = TypeParser::new(&dwarf);
    type_parser.process(&compilation_unit_vec)?;

    let mut parsing_errors = Vec::new();
    for cu in &compilation_unit_vec {
        for error in &cu.parsing_errors {
            parsing_errors.push(format!("Compilation Unit Parsing Error: {}", error));
        }
    }
    for error in type_parser.parsing_errors {
        parsing_errors.push(format!("Type Parsing Error: {}", error));
    }

    // Output the parsed data as JSON
    match &parsing_options.output_file_path {
        Some(output_path) => {
            // Write to specified output file
            let file = File::create(output_path.clone()).unwrap();
            let mut writer = BufWriter::new(file);
            serde_json::to_writer(
                &mut writer,
                &DWARFDebugInformation {
                    compilation_units: &compilation_unit_vec,
                    type_nodes: &type_parser.type_nodes,
                    parsing_errors: parsing_errors,
                },
            )
            .unwrap();
        }
        None => {
            // Write to stdout with pretty formatting
            println!(
                "{}",
                serde_json::to_string_pretty(&DWARFDebugInformation {
                    compilation_units: &compilation_unit_vec,
                    type_nodes: &type_parser.type_nodes,
                    parsing_errors: parsing_errors
                })
                .unwrap()
            );
        }
    }

    Ok(())
}

/// Configuration flags that control the behavior of DWARF parsing and output
///
/// These flags are typically set based on command-line arguments and affect
/// how the DWARF information is processed and formatted.
#[derive(Default)]
struct ParsingOptions {
    /// Whether to demangle symbol names in the output
    /// When true, mangled C++/Rust symbols are converted to human-readable form
    demangle: bool,

    /// Optional output file path for JSON output
    /// If None, output is written to stdout
    output_file_path: Option<String>,

    /// If set to true, extract variables and type information from DWARF.
    extract_variables: bool,
}
