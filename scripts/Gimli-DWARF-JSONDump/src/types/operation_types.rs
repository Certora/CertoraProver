// DWARF Operation Types for JSON Serialization
//
// This module defines JSON-serializable representations of DWARF location operations.
// DWARF uses a stack-based virtual machine to describe where variables are located
// in memory, registers, or computed values. These operations are essential for
// debuggers to find and display variable values during program execution.

use gimli::{Operation, Reader, ReaderOffset};
use serde::{Deserialize, Serialize};

/// JSON-serializable representation of DWARF location operations
///
/// DWARF location expressions use a stack-based virtual machine to describe
/// how to compute the location of a variable or expression. This enum captures
/// the most commonly used operations in a format suitable for JSON serialization.
///
/// Operations not explicitly supported are captured as "Unsupported" with their
/// debug representation for completeness.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq, Hash)]
#[serde(tag = "type")]
pub enum OperationJson {
    /// Variable is stored directly in a CPU register
    /// The register field identifies which register contains the value
    Register {
        /// Hardware register number (architecture-specific)
        register: u16,
    },

    /// Variable is located at an offset from the frame base pointer
    /// Common for local variables and function parameters
    FrameOffset {
        /// Signed offset from frame base (negative for parameters, positive for locals)
        offset: i64,
    },

    /// Variable is at an offset from a base register
    /// More complex addressing mode combining register + offset
    RegisterOffset {
        /// Base register number
        register: u16,
        /// Offset from the register value
        offset: i64,
        /// Type information for the base address
        base_type: String,
    },

    /// Describes a piece of a larger variable (for variables split across locations)
    /// Used when a variable spans multiple registers or memory locations
    Piece {
        /// Size of this piece in bits
        size_in_bits: u64,
        /// Optional bit offset within the location
        bit_offset: Option<u64>,
    },

    /// Push a signed constant value onto the DWARF expression stack
    SignedConstant {
        /// The constant value
        value: i64,
    },

    /// Push an unsigned constant value onto the DWARF expression stack
    UnsignedConstant {
        /// The constant value
        value: u64,
    },

    /// Indicates the top of stack is the variable's value (not its address)
    StackValue,

    /// Subtract the top two stack values (second - first)
    Minus,

    /// Add the top two stack values
    Plus,

    /// Bitwise AND the top two stack values
    And,

    /// Bitwise OR the top two stack values
    Or,

    /// Fallback for DWARF operations not explicitly supported
    /// Preserves the operation information for debugging
    Unsupported {
        /// Debug string representation of the unsupported operation
        operation: String,
    },
}

/// Convert gimli's Operation type to JSON-serializable OperationJson
///
/// This implementation handles the conversion from gimli's internal operation
/// representation to our JSON-friendly format. It processes the most commonly
/// encountered DWARF operations and falls back to "Unsupported" for operations
/// that haven't been explicitly handled.
///
/// # Type Parameters
/// * `R` - The reader type used by gimli for reading DWARF data
/// * `Offset` - The offset type used for addressing within DWARF sections
impl<R, Offset> TryFrom<&Operation<R, Offset>> for OperationJson
where
    R: Reader<Offset = Offset>,
    Offset: ReaderOffset,
{
    type Error = gimli::Error;

    /// Attempt to convert a DWARF operation to JSON format
    ///
    /// # Arguments
    /// * `op` - Reference to the gimli Operation to convert
    ///
    /// # Returns
    /// * `Ok(OperationJson)` - Successfully converted operation
    /// * `Err(gimli::Error)` - Conversion failed (though in practice this is rare)
    fn try_from(op: &Operation<R, Offset>) -> Result<Self, Self::Error> {
        // Pattern match on the operation type and convert to our JSON representation
        return Ok(match op {
            // Frame-relative addressing for local variables
            Operation::FrameOffset { offset } => OperationJson::FrameOffset { offset: *offset },

            // Direct register storage
            Operation::Register { register } => OperationJson::Register {
                register: register.0,
            },

            // Register + offset addressing
            Operation::RegisterOffset {
                register,
                offset,
                base_type,
            } => OperationJson::RegisterOffset {
                register: register.0,
                offset: *offset,
                base_type: format!("{:?}", base_type),
            },

            // Variable piece information (for split variables)
            Operation::Piece {
                size_in_bits,
                bit_offset,
            } => OperationJson::Piece {
                size_in_bits: *size_in_bits,
                bit_offset: *bit_offset,
            },

            // Constant value operations
            Operation::UnsignedConstant { value } => {
                OperationJson::UnsignedConstant { value: *value }
            }
            Operation::SignedConstant { value } => OperationJson::SignedConstant { value: *value },

            // Stack and arithmetic operations
            Operation::StackValue => OperationJson::StackValue,
            Operation::Plus => OperationJson::Plus,
            Operation::Minus => OperationJson::Minus,
            Operation::Or => OperationJson::Or,
            Operation::And => OperationJson::And,

            // Catch-all for operations we don't explicitly handle
            _ => OperationJson::Unsupported {
                operation: format!("{:?}", op),
            },
        });
    }
}
