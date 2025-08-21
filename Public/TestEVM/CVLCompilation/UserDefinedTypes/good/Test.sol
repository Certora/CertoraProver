import './SuperTest.sol';

contract Test is SuperTest {
    constructor() public { }

    type Alias is uint128;

    enum EnumClaw { MountRainier, Auburn, WhiteRiver, TheClaw, Safeway }

    struct StructuralDamage {
        uint128 assessment;
        uint256 year;
    }

    struct SelfDeStruct {
        uint64              time_remaining;
        uint64              the_method;
        EnumClaw            where_to_destroy;
        StructuralDamage    damage;
    }

    EnumClaw public my_storage_value;
    SelfDeStruct public my_struct_storage_value;
    Alias public my_alias;

    function whats_my_alias() public returns (Alias) {
        return my_alias;
    }

    function guess_my_alias(Alias guess) public returns (bool) {
        return Alias.unwrap(guess) == Alias.unwrap(my_alias);
    }

    function is_worst_case(StructuralDamage memory damage) public returns (bool) {
        return damage.assessment > 9000 && damage.year == 2020;
    }

    function is_too_late(SelfDeStruct memory info) public returns (bool) {
        return info.time_remaining < 5 && info.the_method > 4337 && info.where_to_destroy == EnumClaw.Safeway && is_worst_case(info.damage);
    }

    function set_my_enum(EnumClaw e) public {
        if (my_storage_value != e) {
            my_storage_value = e;
        } else if (my_storage_value == EnumClaw.MountRainier) {
            my_storage_value = EnumClaw.Auburn;
        } else {
            my_storage_value = EnumClaw.MountRainier;
        }
    }

    function my_storage_value_is_internal(EnumClaw x) internal returns (bool) {
        return x == my_storage_value;
    }

    function my_storage_value_is(EnumClaw e) public returns (bool) {
        return my_storage_value_is_internal(e);
    }

    function whats_my_struct() internal returns (SelfDeStruct memory) {
        SelfDeStruct memory ret= my_struct_storage_value;
        return ret;
    }

    function guess_my_struct(SelfDeStruct memory guess) public returns (bool) {
        return guess.time_remaining == my_struct_storage_value.time_remaining && guess.the_method == my_struct_storage_value.the_method;
    }

    function external_call_guess_my_struct() external returns (bool) {
        return this.guess_my_struct(whats_my_struct());
    }

    function internal_call_guess_my_struct() external returns (bool) {
        return guess_my_struct(whats_my_struct());
    }

    function choose_an_enum(Alias switcher) public returns (EnumClaw) {
        if (Alias.unwrap(switcher) > Alias.unwrap(my_alias)) {
            return EnumClaw.MountRainier;
        } else {
            return EnumClaw.Safeway;
        }
    }

    function external_call_choose_an_enum(Alias switcher) external returns (EnumClaw) {
        return this.choose_an_enum(switcher);
    }

    function internal_call_choose_an_enum(Alias switcher) external returns (EnumClaw) {
        return choose_an_enum(switcher);
    }
}