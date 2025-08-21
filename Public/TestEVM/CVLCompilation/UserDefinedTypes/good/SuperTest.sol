import './SuperTestLibrary.sol';
import './EnumFile.sol';
contract SuperTest {
    enum EnumInASuperContract { Yeet, McGeet }

    SuperTestLibrary.EnumInALibraryInASuperContract public super_storage_slot;

    function call_enum_and_value_function(SuperTestLibrary.EnumInALibraryInASuperContract x, SuperTestLibrary.SupremeType y) public returns (bool) {
        return SuperTestLibrary.internal_enum_and_value_function_to_summarize(x, y);
    }

    function choose_an_alias(EnumInASuperContract x) public returns (SuperTestLibrary.SupremeType) {
        return SuperTestLibrary.SupremeType.wrap(20);
    }

    function internal_call_choose_an_alias(EnumInASuperContract x) external returns (SuperTestLibrary.SupremeType) {
        return choose_an_alias(x);
    }

    function external_call_choose_an_alias(EnumInASuperContract x) external returns (SuperTestLibrary.SupremeType) {
        return this.choose_an_alias(x);
    }

    function i_return_a_tuple_with_an_enum_and_a_value_type(bool b, uint64 n) external returns (SuperTestLibrary.EnumInALibraryInASuperContract, SuperTestLibrary.SupremeType) {
        SuperTestLibrary.EnumInALibraryInASuperContract ret;
        if (b) {
            ret = SuperTestLibrary.EnumInALibraryInASuperContract.A;
        } else {
            ret = SuperTestLibrary.EnumInALibraryInASuperContract.B;
        }
        return (ret, SuperTestLibrary.SupremeType.wrap(n));
    }
}