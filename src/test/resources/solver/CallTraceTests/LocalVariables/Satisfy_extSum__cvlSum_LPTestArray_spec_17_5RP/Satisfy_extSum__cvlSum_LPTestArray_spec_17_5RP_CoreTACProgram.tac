TACSymbolTable {
	UserDefined {
		UninterpSort skey
	}
	BuiltinFunctions {
		to_skey:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.ToSkey"}
		skey_basic:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.Basic"}
		safe_math_narrow_bv256:JSON{"#class":"vc.data.TACBuiltInFunction.SafeMathNarrow","returnSort":{"bits":256}}
		add_noofl_256:JSON{"#class":"vc.data.TACBuiltInFunction.NoAddOverflowCheck","eName":"add_noofl","paramSorts":[{"#class":"tac.Tag.Bit256"},{"#class":"tac.Tag.Bit256"}],"returnSort":{"#class":"tac.Tag.Bool"},"tag":{"bits":256}}
	}
	UninterpretedFunctions {
	}
	tacM0x40@1:bv256:0
	tacCodesizeCANON0:bv256:1
	tacM0x40!!0:bv256:0
	tacAddress!!28:bv256:2
	lastReverted:bool:3
	tacExtcodesize!!2:wordmap:4
	tacCalldatasize@1:bv256:5
	tacCalldatabuf!!19@1:bytemap:6
	tacCalldatabuf!!22@1:bytemap:6
	tacCalldatabuf!!24@1:bytemap:6
	tacCalldatabuf!!26@1:bytemap:6
	tacExtcodesize:wordmap:4
	tacSighash!!9:bv256:7
	tacCalldatasize!!18:bv256:5
	tacBalance:wordmap:8
	tacBalance!!33:wordmap:8
	tacBalance!!3:wordmap:8
	tacCalldatabuf@1:bytemap:6
	R1:bv256:1
	R5:bv256:9
	R6:bv256:10
	R7:bv256:11
	R8:bv256:12
	B12:bool:13
	B13:bool:13
	B43:bool:14
	B48:bool:14
	B60:bool
	I14:int
	I32:int:13
	I35:int:13
	I46:int
	I51:int
	I52:int
	I54:int
	I57:int
	I58:int
	I59:int
	M16:bytemap:15
	M42:bytemap:15
	M47:bytemap:15
	M53:bytemap:15
	R10:bv256:13
	R11:bv256:13
	R15:bv256:16
	R20:bv256:13
	R21:bv256:13
	R23:bv256:13
	R25:bv256:13
	R29:bv256:13
	R30:bv256:13
	R31:bv256:13
	R34:bv256:13
	R36:bv256:13
	R37:bv256:13
	R38:bv256:17
	R39:bv256:18
	R44:bv256
	R45:bv256:13
	R49:bv256
	R50:bv256:13
	R55:bv256
	R56:bv256:13
	CANON21!4:ghostmap(bv256*bv256*bv256->bv256):19
	tacAddress@1:bv256:2
	LCANON0@1:bv256:17
	LCANON1@1:bv256:18
	CANON10:int:20
	CANON11:int:21
	CANON12:int:22
	CANON13:int:23
	CANON14:int:24
	CANON15:int:25
	CANON16:int
	CANON17:bv256:16
	CANON18:bytemap:15
	CANON19:int:26
	CANON21:ghostmap(bv256*bv256*bv256->bv256):19
	CANON22:bv256:13
	CANON23:bv256:13
	CANON24:bv256:13
	CANON25:bv256:13
	CANON26:bv256:13
	CANON27:bv256:13
	CANON28:bv256:13
	CANON29:bv256:13
	CANON30:int:13
	CANON31:bv256:13
	CANON32:int:13
	CANON33:bv256:13
	CANON34@1:bv256:13
	CANON35:bytemap:15
	CANON36:bool:14
	CANON37:bv256
	CANON38:bv256:13
	CANON39:int
	CANON40:bytemap:15
	CANON41:bool:14
	CANON42:bv256
	CANON43:bv256:13
	CANON44:int
	CANON45:int
	CANON46:bytemap:15
	CANON47:int
	CANON48:bv256
	CANON49:bv256:13
	CANON50:int
	CANON51:int:27
	CANON52:int
	CANON53:int
	CANON54:bool:28
	CANON55:bool
	CANON19!!17:int:26
	CANON19!!41:int:26
	tacContractAtCANON1:bv256:9
	tacContractAtCANON2:bv256:10
	tacContractAtCANON3:bv256:11
	lastHasThrown:bool:29
	CANON1:bv256:13
	CANON2:bool:13
	CANON3:bool:13
	CANON4:bv256:30
	CANON5:bytemap:31
	CANON6:int:32
	CANON7:int:33
	CANON8:int:34
	CANON9:int:35
	tacContractAtCANON:bv256:12
	CANON5havocme457:bytemap:36
	lastHasThrown!!27:bool:29
	lastHasThrown!!40:bool:29
	tacSighash@1:bv256:7
	CANON:bv256:13
}
Program {
	Block 0_0_0_0_0_0 Succ [1_0_0_1_0_0] {
		AssignHavocCmd tacM0x40!!0:0
		AssumeExpCmd Le(tacM0x40!!0:0 0x80 )
		AssignHavocCmd R1:1
		AssignHavocCmd tacExtcodesize!!2:4
		AssignHavocCmd tacBalance!!3:8
		AssignHavocCmd CANON10:20
		AssumeExpCmd LAnd(Ge(CANON10:20 0x0(int) ) Le(CANON10:20 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON11:21
		AssumeExpCmd LAnd(Ge(CANON11:21 0x0(int) ) Le(CANON11:21 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON12:22
		AssumeExpCmd LAnd(Ge(CANON12:22 0x0(int) ) Le(CANON12:22 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON13:23
		AssumeExpCmd LAnd(Ge(CANON13:23 0x0(int) ) Le(CANON13:23 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON14:24
		AssumeExpCmd LAnd(Ge(CANON14:24 0x0(int) ) Le(CANON14:24 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON15:25
		AssumeExpCmd LAnd(Ge(CANON15:25 0x0(int) ) Le(CANON15:25 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON21!4:19
		AssignHavocCmd R5:9
		AssumeExpCmd Eq(R5:9 0x1 )
		AssignHavocCmd R6:10
		AssumeExpCmd Eq(R6:10 0x2 )
		AssignHavocCmd R7:11
		AssumeExpCmd Eq(R7:11 0x4 )
		AssignHavocCmd CANON5:31
		AssignHavocCmd CANON6:32
		AssumeExpCmd LAnd(Ge(CANON6:32 0x0(int) ) Le(CANON6:32 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON7:33
		AssumeExpCmd Eq(CANON7:33 0x0(int) )
		AssignHavocCmd CANON8:34
		AssumeExpCmd LAnd(Ge(CANON8:34 0x0(int) ) Le(CANON8:34 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON9:35
		AssumeExpCmd LAnd(Ge(CANON9:35 0x0(int) ) Le(CANON9:35 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd R8:12
		AssumeExpCmd LAnd(Ge(R8:12 0x1 ) Le(R8:12 0xffffffffffffffffffffffffffffffffffffffff ) )
		AssignHavocCmd CANON5havocme457:36
		AssignHavocCmd tacSighash!!9:7
		AssumeExpCmd Eq(tacSighash!!9:7 0x5b31ae8e )
		AnnotationCmd JSON{"key":{"name":"tac.state.extension","type":"analysis.icfg.Inliner$ExtendedStateVars","erasureStrategy":"Canonical"},"value":"rO0ABXNyACdhbmFseXNpcy5pY2ZnLklubGluZXIkRXh0ZW5kZWRTdGF0ZVZhcnOvh/MjxNFkQAIAAUwAFmluc3RhbmNlVG9FeHRlbmRlZFZhcnN0AA9MamF2YS91dGlsL01hcDt4cHNyACFkYXRhc3RydWN0dXJlcy5MaW5rZWRBcnJheUhhc2hNYXAAAAAAAAAAAQMAAkYACmxvYWRGYWN0b3JMAAloYXNoVGFibGV0AC5MZGF0YXN0cnVjdHVyZXMvYXJyYXloYXNodGFibGUvQXJyYXlIYXNoVGFibGU7eHB3CAAAAAFAAAAAc3IAFGphdmEubWF0aC5CaWdJbnRlZ2VyjPyfH6k7+x0DAAZJAAhiaXRDb3VudEkACWJpdExlbmd0aEkAE2ZpcnN0Tm9uemVyb0J5dGVOdW1JAAxsb3dlc3RTZXRCaXRJAAZzaWdudW1bAAltYWduaXR1ZGV0AAJbQnhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cP///////////////v////4AAAABdXIAAltCrPMX+AYIVOACAAB4cAAAABDORgSgAAAAAAAAAAAAAAABeHNxAH4AA3cIAAAAAEAAAAB4eA=="}
		AnnotationCmd:37 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Setup"}}
		AnnotationCmd:38 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"multi contract setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1}
		AnnotationCmd:39 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"rule parameters setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":2}
		AnnotationCmd:40 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"contract address vars initialized"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":3}
		AnnotationCmd:41 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"setup read tracking instrumentation"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":4}
		AnnotationCmd:42 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"last storage initialize"}}
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":5}
		AnnotationCmd:43 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"}}
		AssignExpCmd R10:13 Select(tacExtcodesize!!2:4 Apply(to_skey:bif R8:12) )
		AssumeExpCmd Ge(R10:13 0x1 )
		AssignExpCmd R11:13 Select(tacExtcodesize!!2:4 Apply(to_skey:bif R8:12) )
		AssumeExpCmd Eq(R11:13 R1:1 )
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":6}
		AnnotationCmd:44 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming address(0).code has no code deployed"}}
		AssignExpCmd B13:13 Eq(Select(tacExtcodesize!!2:4 Apply(skey_basic:bif 0x0) ) 0x0 )
		AssumeCmd B13:13
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":7}
		AnnotationCmd:45 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":8}
		AnnotationCmd:46 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about static addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":9}
		AnnotationCmd:47 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish addresses of precompiled contracts"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":10}
		AnnotationCmd:48 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about uniqueness of contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":11}
		AnnotationCmd:49 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"static links"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":12}
		AnnotationCmd:50 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"record starting nonces"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":13}
		AnnotationCmd:51 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"cloned contracts have no balances"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":14}
		AnnotationCmd:52 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Linked immutable setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":15}
		AnnotationCmd:53 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Constrain immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":16}
		AnnotationCmd:54 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish equivalence of extension and base contract immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":17}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":0}
		AnnotationCmd:55 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"id":"arr","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd CANON4:30 0x3
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"arr194"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.ArrayLength"}],{"namePrefix":"CANON4","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}}}},{"key":{"name":"cvl.lengthof","type":"java.lang.String","erasureStrategy":"Canonical"},"value":"arr"},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"arr.length"}]},[{"#class":"tac.DataField.ArrayData"}],{"namePrefix":"CANON5","tag":{"#class":"tac.Tag.ByteMap"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}}}},{"key":{"name":"cvl.data.is-reference","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":false},{"key":{"name":"cvl.dataof","type":"java.lang.String","erasureStrategy":"Canonical"},"value":"arr"},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"arr"}]}]}}
		AnnotationCmd:56 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":18}
		AnnotationCmd:57 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"id":"e","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"e198"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"coinbase"}],{"namePrefix":"CANON11","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.coinbase"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"basefee"}],{"namePrefix":"CANON9","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.basefee"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"timestamp"}],{"namePrefix":"CANON15","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.timestamp"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"blobbasefee"}],{"namePrefix":"CANON10","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.blobbasefee"}]},[{"#class":"tac.DataField.StructField","field":"tx"},{"#class":"tac.DataField.StructField","field":"origin"}],{"namePrefix":"CANON8","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.tx.origin"}]},[{"#class":"tac.DataField.StructField","field":"msg"},{"#class":"tac.DataField.StructField","field":"sender"}],{"namePrefix":"CANON6","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.msg.sender"}]},[{"#class":"tac.DataField.StructField","field":"msg"},{"#class":"tac.DataField.StructField","field":"value"}],{"namePrefix":"CANON7","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.msg.value"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"difficulty"}],{"namePrefix":"CANON12","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.difficulty"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"gaslimit"}],{"namePrefix":"CANON13","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.gaslimit"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"number"}],{"namePrefix":"CANON14","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.number"}]}]}}
		AnnotationCmd:58 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":19}
		AnnotationCmd:59 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Definition","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":48}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"idL":[{"#class":"spec.cvlast.CVLLhs.Id","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":18}},"id":"extSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":18}}}}],"exp":{"#class":"spec.cvlast.CVLExp.ApplyExp.ContractFunction.Concrete","methodIdWithCallContext":{"#class":"spec.cvlast.ConcreteMethod","signature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"TestArray"},"methodId":"uint256StaticArray"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"arr","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"range":{"#class":"spec.cvlast.CVLRange.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"range":{"#class":"spec.cvlast.CVLRange.Empty"}}]},"sighashInt":{"n":"5b31ae8e"}}},"args":[{"#class":"spec.cvlast.CVLExp.VariableExp","id":"e","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":40},"end":{"line":14,"charByteOffset":41}}},"twoStateIndex":"NEITHER"},{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":43},"end":{"line":14,"charByteOffset":46}}},"twoStateIndex":"NEITHER"}],"noRevert":true,"storage":{"id":"lastStorage","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Empty","comment":"empty storage type"}},"twoStateIndex":"NEITHER"},"isWhole":false,"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.VM","descriptor":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"context":{"#class":"spec.cvlast.typedescriptors.FromVMContext.ReturnValue"}},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":21},"end":{"line":14,"charByteOffset":47}},"annotation":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.CallResolution$DirectPassing","target":{"methodSignature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"TestArray"},"methodId":"uint256StaticArray"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"arr","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"range":{"#class":"spec.cvlast.CVLRange.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"range":{"#class":"spec.cvlast.CVLRange.Empty"}}]},"sighashInt":{"n":"5b31ae8e"}},"definitelyNonPayable":true,"annotation":{"visibility":"EXTERNAL","envFree":false,"library":false,"virtual":false},"stateMutability":"pure","evmExternalMethodInfo":{"sigHash":"5b31ae8e","name":"uint256StaticArray","argTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"}],"resultTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}],"stateMutability":"pure","isConstant":false,"paramNames":["arr"],"isLibrary":false,"contractName":"TestArray","contractInstanceId":"ce4604a0000000000000000000000001","sourceSegment":{"range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}},"content":"function uint256StaticArray(uint256[3] calldata arr) external pure returns (uint256) {\n        return arr[0] + arr[1] + arr[2];\n    }"}}},"hasEnv":true}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"certoraArg206207"},"src":{"id":"e198"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"certoraArg210211"},"src":{"id":"arr194"}}}
		AssignExpCmd R15:16 0x3
		AssignExpCmd M16:15 CANON5havocme457:36
	}
	Block 1_0_0_1_0_0 Succ [2_0_0_0_0_0] {
		AssignHavocCmd:60 CANON19!!17:26
		AnnotationCmd:60 JSON{"key":{"name":"call.trace.push","type":"analysis.icfg.Inliner$CallStack$PushRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"5b31ae8e"},"attr":{"#class":"scene.MethodAttribute.Common"}},"summary":null,"convention":{"#class":"analysis.icfg.Inliner.CallConventionType.FromCVL"},"calleeId":1}}
		AssignHavocCmd:60 tacCalldatasize!!18:5
		AssumeExpCmd Eq(tacCalldatasize!!18:5 0x64 )
		AssignExpCmd:60 tacCalldatabuf!!19@1:61 MapDefinition(CANON20.459:bv256 -> Ite(Lt(CANON20.459 tacCalldatasize!!18:5 ) Select(Select(Select(CANON21!4:19 CANON20.459 ) tacCalldatasize!!18:5 ) 0x5b31ae8e ) 0x0 ) bytemap)
		AssignExpCmd:60 R20:13 Select(Select(Select(CANON21!4:19 0x0 ) 0x64 ) 0x5b31ae8e )
		AssumeExpCmd LAnd(Ge(R20:13 0x5b31ae8e00000000000000000000000000000000000000000000000000000000 ) Le(R20:13 0x5b31ae8effffffffffffffffffffffffffffffffffffffffffffffffffffffff ) )
		AnnotationCmd:60 JSON{"key":{"name":"cvl.arg-serialization.start","type":"spec.CVLInvocationCompiler$StartSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		LabelCmd:60 "7: Read static array from certoraArg210211:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:60 "2: Read element at 0 from certoraArg210211:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:60 "1: Moving primitive..."
		AssignExpCmd R21:13 Select(CANON5havocme457:36 0x0 )
		AssignExpCmd:60 tacCalldatabuf!!22@1:61 Store(tacCalldatabuf!!19@1:61 0x4 R21:13 )
		LabelCmd:60 "...done 1"
		LabelCmd:60 "...done 2"
		LabelCmd:60 "4: Read element at 1 from certoraArg210211:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:60 "3: Moving primitive..."
		AssignExpCmd R23:13 Select(CANON5havocme457:36 0x20 )
		AssignExpCmd:60 tacCalldatabuf!!24@1:61 Store(tacCalldatabuf!!22@1:61 0x24 R23:13 )
		LabelCmd:60 "...done 3"
		LabelCmd:60 "...done 4"
		LabelCmd:60 "6: Read element at 2 from certoraArg210211:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:60 "5: Moving primitive..."
		AssignExpCmd R25:13 Select(CANON5havocme457:36 0x40 )
		AssignExpCmd:60 tacCalldatabuf!!26@1:61 Store(tacCalldatabuf!!24@1:61 0x44 R25:13 )
		LabelCmd:60 "...done 5"
		LabelCmd:60 "...done 6"
		LabelCmd:60 "...done 7"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.external","type":"spec.CVLCompiler$Companion$TraceMeta$ExternalArg","erasureStrategy":"Erased"},"value":{"s":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"certoraArg210211"},"rootOffset":"0","callId":1,"targetType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"sourceType":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"fields":[[{"#class":"tac.DataField.ArrayLength"}],{"namePrefix":"R15","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]},[{"#class":"tac.DataField.ArrayData"}],{"namePrefix":"M16","tag":{"#class":"tac.Tag.ByteMap"},"callIndex":0,"meta":[{"key":{"name":"cvl.data.is-reference","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":false}]}]}}
		AnnotationCmd:60 JSON{"key":{"name":"cvl.arg-serialization.end","type":"spec.CVLInvocationCompiler$EndSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		AssignExpCmd:60 lastHasThrown!!27:29 false
		AssignExpCmd:60 R29:13 Apply(safe_math_narrow_bv256:bif CANON6:32)
		AssignExpCmd:60 R31:13 Select(tacBalance!!3:8 Apply(to_skey:bif R29:13) )
		AssignExpCmd:62 tacBalance!!33:8 Store(tacBalance!!3:8 Apply(to_skey:bif R29:13) R31:13 )
		AssignExpCmd:60 R34:13 Select(tacBalance!!33:8 Apply(to_skey:bif R8:12) )
		AssignExpCmd:62 R36:13 Apply(safe_math_narrow_bv256:bif R34:13)
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.TransferSnippet","srcAccountInfo":{"old":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R31","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"new":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R31","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"addr":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R29","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]}},"trgAccountInfo":{"old":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R34","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"new":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R36","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"addr":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R8","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"tacContractAt"}},{"key":{"name":"tac.contract.sym.addr.name","type":"java.lang.String","erasureStrategy":"Erased"},"value":"TestArray"},{"key":{"name":"tac.contract.sym.addr","type":"java.math.BigInteger","erasureStrategy":"Erased"},"value":"ce4604a0000000000000000000000001"}]}},"transferredAmount":{"#class":"vc.data.TACSymbol.Const","value":"0"}}}
		LabelCmd:60 "Start procedure TestArray-uint256StaticArray(uint256[3])"
		AnnotationCmd:60 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AssignExpCmd:60 R37:13 Select(tacExtcodesize!!2:4 Apply(to_skey:bif R8:63) )
		AssumeExpCmd Ge(R37:13 0x1 )
		AnnotationCmd:60 JSON{"key":{"name":"internal.func.finder.info","type":"analysis.ip.InternalFunctionFinderReport","erasureStrategy":"Erased"},"value":{"unresolvedFunctions":[]}}
		AnnotationCmd:60 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":0,"bytecodeCount":8,"sources":[{"source":0,"begin":61,"end":342}]}}
		LabelCmd "→ Assuming FP is strictly monotonic increasing"
		LabelCmd "←"
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":0,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":3,"charByteOffset":0},"end":{"line":11,"charByteOffset":1}},"content":"contract TestArray {...}"}}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":0}}
		AnnotationCmd:64 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":16,"bytecodeCount":7,"sources":[{"source":0,"begin":61,"end":342}]}}
		AnnotationCmd:64 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":26,"bytecodeCount":9,"sources":[{"source":0,"begin":61,"end":342}]}}
		AnnotationCmd:64 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":59,"bytecodeCount":14,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":552,"bytecodeCount":10,"sources":[{"source":1,"begin":603,"end":915},{"source":1,"begin":736,"end":738},{"source":1,"begin":724,"end":733},{"source":1,"begin":715,"end":722},{"source":1,"begin":711,"end":734},{"source":1,"begin":707,"end":739},{"source":1,"begin":704,"end":706}]}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":1,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}},"content":"compiler-generate condition in function uint256StaticArray(uint256[3] calldata arr) external pure returns (uint256) "}}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":1}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":570,"bytecodeCount":9,"sources":[{"source":1,"begin":704,"end":706},{"source":1,"begin":795,"end":796},{"source":1,"begin":820,"end":898},{"source":1,"begin":890,"end":897},{"source":1,"begin":881,"end":887},{"source":1,"begin":870,"end":879},{"source":1,"begin":866,"end":888}]}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":448,"bytecodeCount":15,"sources":[{"source":1,"begin":25,"end":207},{"source":1,"begin":127,"end":133},{"source":1,"begin":115,"end":133},{"source":1,"begin":180,"end":183},{"source":1,"begin":172,"end":176},{"source":1,"begin":166,"end":170},{"source":1,"begin":162,"end":177},{"source":1,"begin":152,"end":160},{"source":1,"begin":148,"end":178},{"source":1,"begin":145,"end":184},{"source":1,"begin":142,"end":144}]}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":472,"bytecodeCount":6,"sources":[{"source":1,"begin":142,"end":144},{"source":1,"begin":105,"end":207}]}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":584,"bytecodeCount":9,"sources":[{"source":1,"begin":820,"end":898},{"source":1,"begin":810,"end":898},{"source":1,"begin":766,"end":908},{"source":1,"begin":694,"end":915}]}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":80,"bytecodeCount":3,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":155,"bytecodeCount":9,"sources":[{"source":0,"begin":207,"end":340},{"source":0,"begin":283,"end":290},{"source":0,"begin":327,"end":330},{"source":0,"begin":331,"end":332},{"source":0,"begin":327,"end":333}]}}
		AnnotationCmd:66 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":215,"bytecodeCount":12,"sources":[{"source":0,"begin":327,"end":333},{"source":0,"begin":318,"end":321},{"source":0,"begin":322,"end":323},{"source":0,"begin":318,"end":324}]}}
		AnnotationCmd:67 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":278,"bytecodeCount":12,"sources":[{"source":0,"begin":318,"end":324},{"source":0,"begin":309,"end":312},{"source":0,"begin":313,"end":314},{"source":0,"begin":309,"end":315}]}}
		AnnotationCmd:68 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":341,"bytecodeCount":10,"sources":[{"source":0,"begin":309,"end":315},{"source":0,"begin":309,"end":324}]}}
		AnnotationCmd:69 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":704,"bytecodeCount":6,"sources":[{"source":1,"begin":1704,"end":2009},{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1781,"end":1782}]}}
		AnnotationCmd:69 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:69 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":715,"bytecodeCount":7,"sources":[{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1758,"end":1783},{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1815,"end":1816}]}}
		AnnotationCmd:69 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:69 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":726,"bytecodeCount":11,"sources":[{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1792,"end":1817},{"source":1,"begin":1951,"end":1952},{"source":1,"begin":1883,"end":1949},{"source":1,"begin":1879,"end":1953},{"source":1,"begin":1876,"end":1877},{"source":1,"begin":1873,"end":1954},{"source":1,"begin":1870,"end":1872}]}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":2,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":9,"charByteOffset":15},"end":{"line":9,"charByteOffset":30}},"content":"arr[0] + arr[1]"}}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":2}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":779,"bytecodeCount":11,"sources":[{"source":1,"begin":1870,"end":1872},{"source":1,"begin":2001,"end":2002},{"source":1,"begin":1998,"end":1999},{"source":1,"begin":1994,"end":2003},{"source":1,"begin":1987,"end":2003},{"source":1,"begin":1748,"end":2009}]}}
		AssumeExpCmd Apply(add_noofl_256:bif R21:13 R23:13)
		AssignExpCmd:70 R38:17 Apply(safe_math_narrow_bv256:bif IntAdd(R21:13 R23:13))
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":356,"bytecodeCount":6,"sources":[{"source":0,"begin":309,"end":324},{"source":0,"begin":309,"end":333}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":704,"bytecodeCount":6,"sources":[{"source":1,"begin":1704,"end":2009},{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1781,"end":1782}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":715,"bytecodeCount":7,"sources":[{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1758,"end":1783},{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1815,"end":1816}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":726,"bytecodeCount":11,"sources":[{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1792,"end":1817},{"source":1,"begin":1951,"end":1952},{"source":1,"begin":1883,"end":1949},{"source":1,"begin":1879,"end":1953},{"source":1,"begin":1876,"end":1877},{"source":1,"begin":1873,"end":1954},{"source":1,"begin":1870,"end":1872}]}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":3,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":9,"charByteOffset":15},"end":{"line":9,"charByteOffset":39}},"content":"arr[0] + arr[1] + arr[2]"}}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":3}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":779,"bytecodeCount":11,"sources":[{"source":1,"begin":1870,"end":1872},{"source":1,"begin":2001,"end":2002},{"source":1,"begin":1998,"end":1999},{"source":1,"begin":1994,"end":2003},{"source":1,"begin":1987,"end":2003},{"source":1,"begin":1748,"end":2009}]}}
		AssumeExpCmd Apply(add_noofl_256:bif R38:17 R25:13)
		AssignExpCmd:70 R39:18 Apply(safe_math_narrow_bv256:bif IntAdd(R38:17 R25:13))
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":366,"bytecodeCount":7,"sources":[{"source":0,"begin":309,"end":333},{"source":0,"begin":302,"end":333},{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:72 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":85,"bytecodeCount":8,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":677,"bytecodeCount":14,"sources":[{"source":1,"begin":1476,"end":1698},{"source":1,"begin":1607,"end":1609},{"source":1,"begin":1596,"end":1605},{"source":1,"begin":1592,"end":1610},{"source":1,"begin":1584,"end":1610},{"source":1,"begin":1620,"end":1691},{"source":1,"begin":1688,"end":1689},{"source":1,"begin":1677,"end":1686},{"source":1,"begin":1673,"end":1690},{"source":1,"begin":1664,"end":1670}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":662,"bytecodeCount":5,"sources":[{"source":1,"begin":1352,"end":1470},{"source":1,"begin":1439,"end":1463},{"source":1,"begin":1457,"end":1462}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":671,"bytecodeCount":6,"sources":[{"source":1,"begin":1439,"end":1463},{"source":1,"begin":1434,"end":1437},{"source":1,"begin":1427,"end":1464},{"source":1,"begin":1417,"end":1470}]}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite","returnbufOffset":"0","returnValueSym":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R39","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1016}]},"callIndex":1}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":698,"bytecodeCount":6,"sources":[{"source":1,"begin":1620,"end":1691},{"source":1,"begin":1574,"end":1698}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":98,"bytecodeCount":8,"sources":[{"source":0,"begin":207,"end":340}]}}
		AssignExpCmd:60 lastHasThrown!!40:29 false
		AssignExpCmd:60 lastReverted:3 false
		AnnotationCmd:73 JSON{"key":{"name":"tac.return.path","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd:60 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.HaltSnippet.Return","range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}}}}
		LabelCmd:60 "End procedure TestArray-uint256StaticArray(uint256[3])"
		LabelCmd:60 "0: Move primitive value for variable extSum201:int..."
		AssignExpCmd:60 CANON19!!41:26 R39:18
		LabelCmd:60 "...done 0"
		AnnotationCmd:60 JSON{"key":{"name":"call.trace.pop","type":"analysis.icfg.Inliner$CallStack$PopRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"5b31ae8e"},"attr":{"#class":"scene.MethodAttribute.Common"}},"calleeId":1}}
	}
	Block 2_0_0_0_0_0 Succ [] {
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd:60 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":20}
		AnnotationCmd:74 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Definition","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":46}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"idL":[{"#class":"spec.cvlast.CVLLhs.Id","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":18}},"id":"cvlSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":18}}}}],"exp":{"#class":"spec.cvlast.CVLExp.BinaryExp.AddExp","l":{"#class":"spec.cvlast.CVLExp.BinaryExp.AddExp","l":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":24}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"0","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"0"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":25},"end":{"line":15,"charByteOffset":26}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":27}}}},"r":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":30},"end":{"line":15,"charByteOffset":33}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"1"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":34},"end":{"line":15,"charByteOffset":35}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":30},"end":{"line":15,"charByteOffset":36}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":36}}}},"r":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":39},"end":{"line":15,"charByteOffset":42}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"2","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"2"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":43},"end":{"line":15,"charByteOffset":44}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":39},"end":{"line":15,"charByteOffset":45}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":45}}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp357358"},"src":{"id":"arr194"}}}
		AssignExpCmd:75 B43:14 false
		AssignExpCmd:76 R44 0x0
		LabelCmd:76 "12: Read dynamic array..."
		LabelCmd:76 "8: Read array length..."
		LabelCmd:76 "... done 8"
		LabelCmd:76 "11: Read element at tmp359360.361:bv256..."
		LabelCmd:76 "10: Moving primitive..."
		AssignExpCmd R45:13 Select(CANON5havocme457:36 0x0 )
		LabelCmd:76 "9: Move primitive value for variable tmp355356:int..."
		AssignExpCmd:76 I46 R45:13
		LabelCmd:76 "...done 9"
		LabelCmd:76 "...done 10"
		LabelCmd:76 "...done 11"
		LabelCmd:76 "...done 12"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I46","tag":{"#class":"tac.Tag.Int"},"callIndex":0}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R44","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp357358"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp371372"},"src":{"id":"arr194"}}}
		AssignExpCmd:77 B48:14 true
		AssignExpCmd:76 R49 0x1
		LabelCmd:76 "17: Read dynamic array..."
		LabelCmd:76 "13: Read array length..."
		LabelCmd:76 "... done 13"
		LabelCmd:76 "16: Read element at tmp373374.375:bv256..."
		LabelCmd:76 "15: Moving primitive..."
		AssignExpCmd R50:13 Select(CANON5havocme457:36 0x20 )
		LabelCmd:76 "14: Move primitive value for variable tmp369370:int..."
		AssignExpCmd:76 I51 R50:13
		LabelCmd:76 "...done 14"
		LabelCmd:76 "...done 15"
		LabelCmd:76 "...done 16"
		LabelCmd:76 "...done 17"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I51","tag":{"#class":"tac.Tag.Int"},"callIndex":0}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R49","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp371372"}}}
		AssignExpCmd:78 I52 IntAdd(R45:13 R50:13)
		AssumeExpCmd LAnd(Ge(I52 0x0(int) ) Le(I52 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp384385"},"src":{"id":"arr194"}}}
		AssignExpCmd:79 I54 0x2
		AssignExpCmd:76 R55 0x2
		LabelCmd:76 "22: Read dynamic array..."
		LabelCmd:76 "18: Read array length..."
		LabelCmd:76 "... done 18"
		LabelCmd:76 "21: Read element at tmp386387.388:bv256..."
		LabelCmd:76 "20: Moving primitive..."
		AssignExpCmd R56:13 Select(CANON5havocme457:36 0x40 )
		LabelCmd:76 "19: Move primitive value for variable tmp382383:int..."
		AssignExpCmd:76 I57 R56:13
		LabelCmd:76 "...done 19"
		LabelCmd:76 "...done 20"
		LabelCmd:76 "...done 21"
		LabelCmd:76 "...done 22"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I57","tag":{"#class":"tac.Tag.Int"},"callIndex":0}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R55","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp384385"}}}
		AssignExpCmd:80 CANON51:27 IntAdd(I52 R56:13)
		AssumeExpCmd LAnd(Ge(CANON51:27 0x0(int) ) Le(CANON51:27 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AnnotationCmd:76 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":21}
		AnnotationCmd:81 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Satisfy","cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":4},"end":{"line":16,"charByteOffset":29}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.EqExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"extSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":12},"end":{"line":16,"charByteOffset":18}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"cvlSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":22},"end":{"line":16,"charByteOffset":28}}},"twoStateIndex":"NEITHER"},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"cvlRange":{"#class":"spec.cvlast.CVLRange.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":12},"end":{"line":16,"charByteOffset":28}}}},"description":"extSum == cvlSum","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd:82 I58 CANON19!!41:26
		AssignExpCmd:83 I59 CANON51:27
		AssignExpCmd:84 CANON54:28 Eq(I58 I59 )
		AssumeCmd CANON54:28
		AssignExpCmd:85 B60 false
		AssertCmd:86 false "extSum == cvlSum"
	}
}
Axioms {
}
Metas {
  "0": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacM0x40",
        "maybeTACKeywordOrdinal": 39
      }
    },
    {
      "key": {
        "name": "tac.is.reserved.memory.slot.var",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "40"
    }
  ],
  "1": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacCodesize",
        "maybeTACKeywordOrdinal": 7
      }
    },
    {
      "key": {
        "name": "tac.codesize",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    },
    {
      "key": {
        "name": "tac.no.callindex",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "2": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacAddress",
        "maybeTACKeywordOrdinal": 22
      }
    },
    {
      "key": {
        "name": "tac.env.known-bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 160
    }
  ],
  "3": [
    {
      "key": {
        "name": "tac.last.reverted",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule"
      }
    },
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastReverted"
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "lastReverted"
    }
  ],
  "4": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacExtcodesize",
        "maybeTACKeywordOrdinal": 25
      }
    },
    {
      "key": {
        "name": "tac.no.callindex",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "tac.is.extcodesize",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "5": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacCalldatasize",
        "maybeTACKeywordOrdinal": 12
      }
    }
  ],
  "6": [
    {
      "key": {
        "name": "tac.immutable.array",
        "type": "vc.data.ImmutableBound",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "sym": {
          "#class": "vc.data.TACSymbol.Var.Full",
          "namePrefix": "tacCalldatasize",
          "tag": {
            "#class": "tac.Tag.Bit256"
          },
          "callIndex": 1,
          "meta": [
            {
              "key": {
                "name": "Tac.symbol.keyword",
                "type": "vc.data.TACSymbol$Var$KeywordEntry",
                "erasureStrategy": "Canonical"
              },
              "value": {
                "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
                "name": "tacCalldatasize",
                "maybeTACKeywordOrdinal": 12
              }
            }
          ]
        }
      }
    },
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacCalldatabuf",
        "maybeTACKeywordOrdinal": 15
      }
    },
    {
      "key": {
        "name": "tac.is.calldata",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "7": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacSighash",
        "maybeTACKeywordOrdinal": 6
      }
    }
  ],
  "8": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacBalance",
        "maybeTACKeywordOrdinal": 23
      }
    },
    {
      "key": {
        "name": "tac.no.callindex",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "9": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "tacContractAt"
      }
    },
    {
      "key": {
        "name": "tac.contract.sym.addr.name",
        "type": "java.lang.String",
        "erasureStrategy": "Erased"
      },
      "value": "ecrecover"
    },
    {
      "key": {
        "name": "tac.contract.sym.addr",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Erased"
      },
      "value": "1"
    }
  ],
  "10": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "tacContractAt"
      }
    },
    {
      "key": {
        "name": "tac.contract.sym.addr.name",
        "type": "java.lang.String",
        "erasureStrategy": "Erased"
      },
      "value": "sha256"
    },
    {
      "key": {
        "name": "tac.contract.sym.addr",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Erased"
      },
      "value": "2"
    }
  ],
  "11": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "tacContractAt"
      }
    },
    {
      "key": {
        "name": "tac.contract.sym.addr.name",
        "type": "java.lang.String",
        "erasureStrategy": "Erased"
      },
      "value": "identity"
    },
    {
      "key": {
        "name": "tac.contract.sym.addr",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Erased"
      },
      "value": "4"
    }
  ],
  "12": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "tacContractAt"
      }
    },
    {
      "key": {
        "name": "tac.contract.sym.addr.name",
        "type": "java.lang.String",
        "erasureStrategy": "Erased"
      },
      "value": "TestArray"
    },
    {
      "key": {
        "name": "tac.contract.sym.addr",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Erased"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "13": [
    {
      "key": {
        "name": "tac.is-temp-var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "14": [
    {
      "key": {
        "name": "tac.was.replaced.with.bool",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "15": [
    {
      "key": {
        "name": "cvl.data.is-reference",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": false
    }
  ],
  "16": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    }
  ],
  "17": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "L",
        "maybeTACKeywordOrdinal": 45
      }
    },
    {
      "key": {
        "name": "tac.stack.height",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1015
    }
  ],
  "18": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "L",
        "maybeTACKeywordOrdinal": 45
      }
    },
    {
      "key": {
        "name": "tac.stack.height",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1016
    }
  ],
  "19": [
    {
      "key": {
        "name": "tac.no.callindex",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "20": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "blobbasefee",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.blobbasefee"
    }
  ],
  "21": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "coinbase",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.coinbase"
    }
  ],
  "22": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "difficulty",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.difficulty"
    }
  ],
  "23": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "gaslimit",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.gaslimit"
    }
  ],
  "24": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "number",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.number"
    }
  ],
  "25": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "timestamp",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.timestamp"
    }
  ],
  "26": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 14,
            "charByteOffset": 4
          },
          "end": {
            "line": 14,
            "charByteOffset": 18
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "extSum"
    }
  ],
  "27": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 15,
            "charByteOffset": 4
          },
          "end": {
            "line": 15,
            "charByteOffset": 18
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "cvlSum"
    }
  ],
  "28": [
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    }
  ],
  "29": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule"
      }
    },
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastHasThrown"
      }
    },
    {
      "key": {
        "name": "tac.last.has.thrown",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "lastHasThrown"
    }
  ],
  "30": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 12,
            "charByteOffset": 4
          },
          "end": {
            "line": 12,
            "charByteOffset": 19
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.lengthof",
        "type": "java.lang.String",
        "erasureStrategy": "Canonical"
      },
      "value": "arr"
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "arr.length"
    }
  ],
  "31": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 12,
            "charByteOffset": 4
          },
          "end": {
            "line": 12,
            "charByteOffset": 19
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.data.is-reference",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": false
    },
    {
      "key": {
        "name": "cvl.dataof",
        "type": "java.lang.String",
        "erasureStrategy": "Canonical"
      },
      "value": "arr"
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
        "elementType": {
          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
          "k": 256
        },
        "logicalLength": "3"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "arr"
    }
  ],
  "32": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "msg",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "msg",
              "fields": [
                {
                  "fieldName": "sender",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "value",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "sender",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.msg.sender"
    }
  ],
  "33": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "msg",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "msg",
              "fields": [
                {
                  "fieldName": "sender",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "value",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "value",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.msg.value"
    }
  ],
  "34": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "tx",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "tx",
              "fields": [
                {
                  "fieldName": "origin",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "origin",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.tx.origin"
    }
  ],
  "35": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "env",
          "fields": [
            {
              "fieldName": "msg",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "msg",
                "fields": [
                  {
                    "fieldName": "sender",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "value",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "tx",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "tx",
                "fields": [
                  {
                    "fieldName": "origin",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "block",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "block",
                "fields": [
                  {
                    "fieldName": "basefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "blobbasefee",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "coinbase",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "difficulty",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "gaslimit",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "number",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "timestamp",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "block",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "block",
              "fields": [
                {
                  "fieldName": "basefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "blobbasefee",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "coinbase",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "difficulty",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "gaslimit",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "number",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "timestamp",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                }
              ]
            }
          },
          {
            "fieldName": "basefee",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 256
            }
          }
        ]
      }
    },
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 13,
            "charByteOffset": 4
          },
          "end": {
            "line": 13,
            "charByteOffset": 10
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 256
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "e.block.basefee"
    }
  ],
  "36": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "spec.cvlast.CVLRange.Range",
          "specFile": "TestArray.spec",
          "start": {
            "line": 12,
            "charByteOffset": 4
          },
          "end": {
            "line": 12,
            "charByteOffset": 19
          }
        }
      }
    },
    {
      "key": {
        "name": "tacsimplesimple.havocme",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.data.is-reference",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": false
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
        "elementType": {
          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
          "k": 256
        },
        "logicalLength": "3"
      }
    },
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "arr"
    }
  ],
  "37": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    }
  ],
  "38": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1
    }
  ],
  "39": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 2
    }
  ],
  "40": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 3
    }
  ],
  "41": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 4
    }
  ],
  "42": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 5
    }
  ],
  "43": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 6
    }
  ],
  "44": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 7
    }
  ],
  "45": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    }
  ],
  "46": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 9
    }
  ],
  "47": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 10
    }
  ],
  "48": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 11
    }
  ],
  "49": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 12
    }
  ],
  "50": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 13
    }
  ],
  "51": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 14
    }
  ],
  "52": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 15
    }
  ],
  "53": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 16
    }
  ],
  "54": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 17
    }
  ],
  "55": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 12,
          "charByteOffset": 4
        },
        "end": {
          "line": 12,
          "charByteOffset": 19
        }
      }
    },
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 18
    }
  ],
  "56": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 12,
          "charByteOffset": 4
        },
        "end": {
          "line": 12,
          "charByteOffset": 19
        }
      }
    }
  ],
  "57": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 13,
          "charByteOffset": 4
        },
        "end": {
          "line": 13,
          "charByteOffset": 10
        }
      }
    },
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 19
    }
  ],
  "58": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 13,
          "charByteOffset": 4
        },
        "end": {
          "line": 13,
          "charByteOffset": 10
        }
      }
    }
  ],
  "59": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 20
    }
  ],
  "60": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    }
  ],
  "61": [
    {
      "key": {
        "name": "tac.immutable.array",
        "type": "vc.data.ImmutableBound",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "sym": {
          "#class": "vc.data.TACSymbol.Var.Full",
          "namePrefix": "tacCalldatasize!!18",
          "tag": {
            "#class": "tac.Tag.Bit256"
          },
          "callIndex": 0,
          "meta": [
            {
              "key": {
                "name": "Tac.symbol.keyword",
                "type": "vc.data.TACSymbol$Var$KeywordEntry",
                "erasureStrategy": "Canonical"
              },
              "value": {
                "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
                "name": "tacCalldatasize",
                "maybeTACKeywordOrdinal": 12
              }
            }
          ]
        }
      }
    },
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacCalldatabuf",
        "maybeTACKeywordOrdinal": 15
      }
    },
    {
      "key": {
        "name": "tac.is.calldata",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "62": [
    {
      "key": {
        "name": "tac.transfers.balance",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    }
  ],
  "63": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacAddress",
        "maybeTACKeywordOrdinal": 22
      }
    },
    {
      "key": {
        "name": "tac.env.known-bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 160
    },
    {
      "key": {
        "name": "tac.contract.sym.addr.name",
        "type": "java.lang.String",
        "erasureStrategy": "Erased"
      },
      "value": "TestArray"
    },
    {
      "key": {
        "name": "tac.contract.sym.addr",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Erased"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "64": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 61,
        "len": 281,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "65": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 207,
        "len": 133,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "66": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 327,
        "len": 6,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "67": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 318,
        "len": 6,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "68": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 309,
        "len": 6,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "69": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 309,
        "len": 15,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "70": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "overflow.rewrite",
        "type": "analysis.opt.overflow.OverflowPatternRewriter$Companion$OverflowMetaData",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "type": "Add",
        "width": 256,
        "signed": false
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 1,
        "begin": 1994,
        "len": 9,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "71": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 309,
        "len": 24,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "72": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 207,
        "len": 133,
        "jumpType": "EXIT",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "73": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 14,
          "charByteOffset": 4
        },
        "end": {
          "line": 14,
          "charByteOffset": 48
        }
      }
    },
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "source": 0,
        "begin": 207,
        "len": 133,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "TestArray.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    }
  ],
  "74": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    },
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 21
    }
  ],
  "75": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
          "n": "0",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
              "n": "0"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 15,
                "charByteOffset": 25
              },
              "end": {
                "line": 15,
                "charByteOffset": 26
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "76": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "77": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
          "n": "1",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
              "n": "1"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 15,
                "charByteOffset": 34
              },
              "end": {
                "line": 15,
                "charByteOffset": 35
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "78": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.BinaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.BinaryExp.AddExp",
          "l": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 24
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "0",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "0"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 25
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 26
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 27
                }
              }
            }
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 30
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 33
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "1",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "1"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 34
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 35
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 30
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 36
                }
              }
            }
          },
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 15,
                "charByteOffset": 21
              },
              "end": {
                "line": 15,
                "charByteOffset": 36
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "R45",
            "tag": {
              "#class": "tac.Tag.Bit256"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "tac.is-temp-var",
                  "type": "tac.MetaMap$Companion$NothingValue",
                  "erasureStrategy": "Canonical"
                },
                "value": {
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 24
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "0",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "0"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 25
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 26
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 27
                }
              }
            }
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "R50",
            "tag": {
              "#class": "tac.Tag.Bit256"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "tac.is-temp-var",
                  "type": "tac.MetaMap$Companion$NothingValue",
                  "erasureStrategy": "Canonical"
                },
                "value": {
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 30
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 33
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "1",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "1"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 34
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 35
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 30
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 36
                }
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "79": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
          "n": "2",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
              "n": "2"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 15,
                "charByteOffset": 43
              },
              "end": {
                "line": 15,
                "charByteOffset": 44
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "80": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.BinaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.BinaryExp.AddExp",
          "l": {
            "#class": "spec.cvlast.CVLExp.BinaryExp.AddExp",
            "l": {
              "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
              "array": {
                "#class": "spec.cvlast.CVLExp.VariableExp",
                "id": "arr",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                    "elementType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    },
                    "logicalLength": "3"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 21
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 24
                    }
                  }
                },
                "twoStateIndex": "NEITHER"
              },
              "index": {
                "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
                "n": "0",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                    "n": "0"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 25
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 26
                    }
                  }
                }
              },
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 27
                  }
                }
              }
            },
            "r": {
              "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
              "array": {
                "#class": "spec.cvlast.CVLExp.VariableExp",
                "id": "arr",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                    "elementType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    },
                    "logicalLength": "3"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 30
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 33
                    }
                  }
                },
                "twoStateIndex": "NEITHER"
              },
              "index": {
                "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
                "n": "1",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                    "n": "1"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 34
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 35
                    }
                  }
                }
              },
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 30
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 36
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 36
                }
              }
            }
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 39
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 42
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "2",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "2"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 43
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 44
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 39
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 45
                }
              }
            }
          },
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 15,
                "charByteOffset": 21
              },
              "end": {
                "line": 15,
                "charByteOffset": 45
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I52",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.BinaryExp.AddExp",
            "l": {
              "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
              "array": {
                "#class": "spec.cvlast.CVLExp.VariableExp",
                "id": "arr",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                    "elementType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    },
                    "logicalLength": "3"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 21
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 24
                    }
                  }
                },
                "twoStateIndex": "NEITHER"
              },
              "index": {
                "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
                "n": "0",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                    "n": "0"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 25
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 26
                    }
                  }
                }
              },
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 27
                  }
                }
              }
            },
            "r": {
              "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
              "array": {
                "#class": "spec.cvlast.CVLExp.VariableExp",
                "id": "arr",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                    "elementType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    },
                    "logicalLength": "3"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 30
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 33
                    }
                  }
                },
                "twoStateIndex": "NEITHER"
              },
              "index": {
                "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
                "n": "1",
                "tag": {
                  "scope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      },
                      {
                        "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                        "scopeId": 1
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                        {
                          "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                        }
                      ],
                      "innerScope": {
                        "scopeStack": [
                        ],
                        "innerScope": null
                      }
                    }
                  },
                  "type": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                    "n": "1"
                  },
                  "cvlRange": {
                    "#class": "spec.cvlast.CVLRange.Range",
                    "specFile": "TestArray.spec",
                    "start": {
                      "line": 15,
                      "charByteOffset": 34
                    },
                    "end": {
                      "line": 15,
                      "charByteOffset": 35
                    }
                  }
                }
              },
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 30
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 36
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 36
                }
              }
            }
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "R56",
            "tag": {
              "#class": "tac.Tag.Bit256"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "tac.is-temp-var",
                  "type": "tac.MetaMap$Companion$NothingValue",
                  "erasureStrategy": "Canonical"
                },
                "value": {
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.ArrayDerefExp",
            "array": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "arr",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.StaticArray",
                  "elementType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  },
                  "logicalLength": "3"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 39
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 42
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "index": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "2",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 1
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                      {
                        "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                      }
                    ],
                    "innerScope": {
                      "scopeStack": [
                      ],
                      "innerScope": null
                    }
                  }
                },
                "type": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "2"
                },
                "cvlRange": {
                  "#class": "spec.cvlast.CVLRange.Range",
                  "specFile": "TestArray.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 43
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 44
                  }
                }
              }
            },
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 39
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 45
                }
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 46
        }
      }
    }
  ],
  "81": [
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    },
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 22
    }
  ],
  "82": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.VariableExp",
          "id": "extSum",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 16,
                "charByteOffset": 12
              },
              "end": {
                "line": 16,
                "charByteOffset": 18
              }
            }
          },
          "twoStateIndex": "NEITHER"
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    }
  ],
  "83": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.VariableExp",
          "id": "cvlSum",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 16,
                "charByteOffset": 22
              },
              "end": {
                "line": 16,
                "charByteOffset": 28
              }
            }
          },
          "twoStateIndex": "NEITHER"
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    }
  ],
  "84": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.BinaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.RelopExp.EqExp",
          "l": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "extSum",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 16,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 16,
                  "charByteOffset": 18
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "cvlSum",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 16,
                  "charByteOffset": 22
                },
                "end": {
                  "line": 16,
                  "charByteOffset": 28
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 16,
                "charByteOffset": 12
              },
              "end": {
                "line": 16,
                "charByteOffset": 28
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I58",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "extSum",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 16,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 16,
                  "charByteOffset": 18
                }
              }
            },
            "twoStateIndex": "NEITHER"
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I59",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "cvlSum",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 1
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    }
                  ],
                  "innerScope": {
                    "scopeStack": [
                    ],
                    "innerScope": null
                  }
                }
              },
              "type": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
              },
              "cvlRange": {
                "#class": "spec.cvlast.CVLRange.Range",
                "specFile": "TestArray.spec",
                "start": {
                  "line": 16,
                  "charByteOffset": 22
                },
                "end": {
                  "line": 16,
                  "charByteOffset": 28
                }
              }
            },
            "twoStateIndex": "NEITHER"
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    }
  ],
  "85": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.Constant.BoolLit",
          "b": "0",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 1
                }
              ],
              "innerScope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  }
                ],
                "innerScope": {
                  "scopeStack": [
                  ],
                  "innerScope": null
                }
              }
            },
            "type": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
            },
            "cvlRange": {
              "#class": "spec.cvlast.CVLRange.Range",
              "specFile": "TestArray.spec",
              "start": {
                "line": 16,
                "charByteOffset": 4
              },
              "end": {
                "line": 16,
                "charByteOffset": 29
              }
            }
          }
        }
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    }
  ],
  "86": [
    {
      "key": {
        "name": "cvl.user.defined.assert",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "spec.cvlast.CVLRange",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLRange.Range",
        "specFile": "TestArray.spec",
        "start": {
          "line": 16,
          "charByteOffset": 4
        },
        "end": {
          "line": 16,
          "charByteOffset": 29
        }
      }
    },
    {
      "key": {
        "name": "satisfy.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    }
  ]
}