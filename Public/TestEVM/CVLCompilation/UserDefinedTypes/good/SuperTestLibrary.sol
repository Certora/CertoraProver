library SuperTestLibrary {
    type SupremeType is uint64;
    enum EnumInALibraryInASuperContract { A, B, C }
    function internal_enum_and_value_function_to_summarize(EnumInALibraryInASuperContract x, SupremeType y) internal returns (bool) {
        return x == EnumInALibraryInASuperContract.A || SupremeType.unwrap(y) > uint8(x);
    }
}