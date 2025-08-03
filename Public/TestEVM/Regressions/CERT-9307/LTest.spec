methods {
   unresolved external in _.delegateAndGet() => DISPATCH(optimistic=true) [
     LHarness.doGet()
   ];

   unresolved external in _.delegateAndRevert(uint) => DISPATCH(optimistic=true) [
     LHarness.doSet()
   ];

}

rule foo {
  env e;
  uint res = test(e);
  satisfy res != 3;
}