methods {
	function _.setFlag() external => DISPATCHER(optimistic=true);
}

rule possible_to_return() {
	env e;
	entry@withrevert(e);
	satisfy !lastReverted;
}
