// Rust Type System Representation for DWARF Debugging Information
//
// This module defines a comprehensive type system for representing Rust types
// that have been parsed from DWARF debugging information. The goal is to provide
// a structured, JSON-serializable representation of Rust's type system that can
// be used for debugging tools, type analysis, and other applications that need
// to understand the structure of Rust programs from compiled binaries.

use serde::{Deserialize, Serialize, Serializer};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Deserialize)]
pub struct RustTypeId(pub usize, pub usize);

impl Serialize for RustTypeId {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&format!("{}_{}", self.0, self.1))
    }
}

/// Comprehensive enumeration of Rust types that can be represented in DWARF debug info
///
/// This enum covers the full spectrum of Rust's type system, from primitive types
/// to complex generic constructs. Each variant includes the necessary information
/// to fully represent the type's structure and properties.
///
/// The enum is designed to be JSON-serializable with tagged union semantics,
/// making it suitable for interchange with external tools and long-term storage.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum RustType {
    // Primitive signed integer types
    I8,
    /// 8-bit signed integer
    I16,
    /// 16-bit signed integer
    I32,
    /// 32-bit signed integer
    I64,
    /// 64-bit signed integer
    I128,
    /// 128-bit signed integer
    Isize,
    /// Pointer-sized signed integer
    // Primitive unsigned integer types
    U8,
    /// 8-bit unsigned integer
    U16,
    /// 16-bit unsigned integer
    U32,
    /// 32-bit unsigned integer
    U64,
    /// 64-bit unsigned integer
    U128,
    /// 128-bit unsigned integer
    Usize,
    /// Pointer-sized unsigned integer
    // Floating point types
    F32,
    /// 32-bit floating point (single precision)
    F64,
    /// 64-bit floating point (double precision)
    // Boolean and character types
    Bool,
    /// Boolean type (true/false)
    // Reference types (&T, &mut T)
    Reference {
        /// The type being referenced
        referent_id: Box<RustTypeId>,
    },

    // Fixed-size array types ([T; N])
    Array {
        /// The type of elements in the array
        element_type_id: Box<RustTypeId>,
        /// The number of elements (compile-time constant)
        element_count: u64,
    },

    // Struct types - product types with named fields
    Struct {
        /// Name of the struct type
        name: String,
        /// Total size of the struct in bytes
        size: u64,
        /// Fields of the struct with their types and offsets
        fields: Vec<StructField>,
        /// Whether this is a tuple struct (fields accessed by position)
        is_tuple_struct: bool,
    },

    // Enum types - sum types with multiple variants
    Enum {
        /// Name of the enum type
        name: String,
        /// All possible variants of this enum
        variants: Vec<EnumVariant>,
    },

    VariantPart {
        discriminant: Discriminant,
        variants: Vec<Variant>,
        size: u64,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Variant {
    pub discr_name: String,
    pub discr_value: u64,
    pub rust_type_id: RustTypeId,
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Discriminant {
    pub rust_type_id: RustTypeId,
    pub offset: u64,
}

/// Represents a field within a struct type
///
/// Contains all the information needed to understand the field's layout,
/// type, and position within the containing struct.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct StructField {
    /// Name of the field as it appears in source code
    pub name: String,
    /// Type of this field
    pub field_type_id: RustTypeId,
    /// Byte offset of this field from the start of the struct
    pub offset: u64,
}

/// Represents a single variant within an enum type
///
/// Each enum variant has a name and can contain data in different formats:
/// unit variants (no data), tuple variants (positional data), or struct variants (named data).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct EnumVariant {
    /// Name of the variant as it appears in source code
    pub name: String,
    /// The type of data this variant holds
    pub variant_type: EnumVariantType,
}

/// Describes the different ways an enum variant can hold data
///
/// Rust enum variants can be:
/// - Unit variants: no associated data (e.g., `None`, `Red`)
/// - Tuple variants: positional data (e.g., `Some(T)`, `Point(x, y)`)
/// - Struct variants: named data (e.g., `Person { name: String, age: u32 }`)
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum EnumVariantType {
    /// Unit variant with no associated data
    Unit,
    /// Tuple variant with positional fields
    Tuple(Vec<RustType>),
    /// Struct variant with named fields
    Struct(Vec<StructField>),
}
