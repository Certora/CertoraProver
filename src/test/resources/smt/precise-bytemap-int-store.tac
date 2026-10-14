TACSymbolTable {
	UserDefined {
	}
	BuiltinFunctions {
	}
	UninterpretedFunctions {
	}
	m0:bytemap
	m1:bytemap
	i:bv256
	j:bv256
	v:int
	r:bv256
	isZero:bool
}
Program {
	Block 0_0_0_0_0_0 Succ [] {
		AssignExpCmd m1 Store(m0 i v)
		AssignExpCmd r Select(m1 j)
		AssignExpCmd isZero Eq(r 0x0)
		AssertCmd isZero ""
	}
}
Axioms {
}
Metas {
}
