import "Basic.spec";

rule other_sanity {
  env e;
  method m;
  calldataarg args;
  m(e, args);
  satisfy true, "sanity";
}
