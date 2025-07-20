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
	tacAddress!!26:bv256:2
	lastReverted:bool:3
	tacExtcodesize!!2:wordmap:4
	tacCalldatasize@1:bv256:5
	tacCalldatabuf!!18@1:bytemap:6
	tacCalldatabuf!!21@1:bytemap:6
	tacCalldatabuf!!23@1:bytemap:6
	tacCalldatabuf!!25@1:bytemap:6
	tacExtcodesize:wordmap:4
	tacSighash!!9:bv256:7
	tacCalldatasize!!17:bv256:5
	tacBalance:wordmap:8
	tacBalance!!31:wordmap:8
	tacBalance!!3:wordmap:8
	tacCalldatabuf@1:bytemap:6
	R1:bv256:1
	R5:bv256:9
	R6:bv256:10
	R7:bv256:11
	R8:bv256:12
	B11:bool:13
	B12:bool:13
	B40:bool:14
	B45:bool:15
	B57:bool:16
	I13:int:17
	I30:int:13
	I33:int:13
	I43:int:18
	I48:int:18
	I49:int:19
	I51:int:20
	I54:int:18
	I55:int:19
	I56:int:19
	M15:bytemap:21
	M39:bytemap:21
	M44:bytemap:21
	M50:bytemap:21
	R10:bv256:13
	R14:bv256:18
	R19:bv256:13
	R20:bv256:13
	R22:bv256:13
	R24:bv256:13
	R27:bv256:13
	R28:bv256:13
	R29:bv256:13
	R32:bv256:13
	R34:bv256:13
	R35:bv256:13
	R36:bv256:22
	R37:bv256:23
	R41:bv256:24
	R42:bv256:13
	R46:bv256:25
	R47:bv256:13
	R52:bv256:20
	R53:bv256:13
	CANON21!4:ghostmap(bv256*bv256*bv256->bv256):26
	tacAddress@1:bv256:2
	LCANON0@1:bv256:22
	LCANON1@1:bv256:23
	CANON10:int:27
	CANON11:int:28
	CANON12:int:29
	CANON13:int:30
	CANON14:int:31
	CANON15:int:32
	CANON16:int:17
	CANON17:bv256:18
	CANON18:bytemap:21
	CANON19:int:33
	CANON21:ghostmap(bv256*bv256*bv256->bv256):26
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
	CANON35:bytemap:21
	CANON36:bool:14
	CANON37:bv256:24
	CANON38:bv256:13
	CANON39:int:18
	CANON40:bytemap:21
	CANON41:bool:15
	CANON42:bv256:25
	CANON43:bv256:13
	CANON44:int:18
	CANON45:int:19
	CANON46:bytemap:21
	CANON47:int:20
	CANON48:bv256:20
	CANON49:bv256:13
	CANON50:int:18
	CANON51:int:34
	CANON52:int:19
	CANON53:int:19
	CANON54:bool:35
	CANON55:bool:16
	CANON19!!16:int:33
	CANON19!!38:int:33
	tacContractAtCANON1:bv256:9
	tacContractAtCANON2:bv256:10
	tacContractAtCANON3:bv256:11
	CANON1:bv256:13
	CANON2:bool:13
	CANON3:bool:13
	CANON4:bv256:36
	CANON5:bytemap:37
	CANON6:int:38
	CANON7:int:39
	CANON8:int:40
	CANON9:int:41
	tacContractAtCANON:bv256:12
	CANON5havocme656:bytemap:42
	tacSighash@1:bv256:7
	CANON:int:43
}
Program {
	Block 0_0_0_0_0_0 Succ [1_0_0_1_0_0] {
		AssignHavocCmd tacM0x40!!0:0
		AssumeExpCmd Le(tacM0x40!!0:0 0x80 )
		AssignHavocCmd R1:1
		AssumeExpCmd Ge(R1:1 0x1 )
		AssignHavocCmd tacExtcodesize!!2:4
		AssignHavocCmd tacBalance!!3:8
		AssignHavocCmd CANON10:27
		AssumeExpCmd LAnd(Ge(CANON10:27 0x0(int) ) Le(CANON10:27 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON11:28
		AssumeExpCmd LAnd(Ge(CANON11:28 0x0(int) ) Le(CANON11:28 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON12:29
		AssumeExpCmd LAnd(Ge(CANON12:29 0x0(int) ) Le(CANON12:29 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON13:30
		AssumeExpCmd LAnd(Ge(CANON13:30 0x0(int) ) Le(CANON13:30 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON14:31
		AssumeExpCmd LAnd(Ge(CANON14:31 0x0(int) ) Le(CANON14:31 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON15:32
		AssumeExpCmd LAnd(Ge(CANON15:32 0x0(int) ) Le(CANON15:32 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON21!4:26
		AssignHavocCmd R5:9
		AssumeExpCmd Eq(R5:9 0x1 )
		AssignHavocCmd R6:10
		AssumeExpCmd Eq(R6:10 0x2 )
		AssignHavocCmd R7:11
		AssumeExpCmd Eq(R7:11 0x4 )
		AssignHavocCmd CANON5:37
		AssignHavocCmd CANON6:38
		AssumeExpCmd LAnd(Ge(CANON6:38 0x0(int) ) Le(CANON6:38 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON7:39
		AssumeExpCmd Eq(CANON7:39 0x0(int) )
		AssignHavocCmd CANON8:40
		AssumeExpCmd LAnd(Ge(CANON8:40 0x0(int) ) Le(CANON8:40 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON9:41
		AssumeExpCmd LAnd(Ge(CANON9:41 0x0(int) ) Le(CANON9:41 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd R8:12
		AssumeExpCmd LAnd(Ge(R8:12 0x1 ) Le(R8:12 0xffffffffffffffffffffffffffffffffffffffff ) )
		AssignHavocCmd CANON5havocme656:42
		AssignHavocCmd tacSighash!!9:7
		AssumeExpCmd Eq(tacSighash!!9:7 0x5b31ae8e )
		AnnotationCmd JSON{"key":{"name":"tac.state.extension","type":"analysis.icfg.Inliner$ExtendedStateVars","erasureStrategy":"Canonical"},"value":"rO0ABXNyACdhbmFseXNpcy5pY2ZnLklubGluZXIkRXh0ZW5kZWRTdGF0ZVZhcnOvh/MjxNFkQAIAAUwAFmluc3RhbmNlVG9FeHRlbmRlZFZhcnN0AA9MamF2YS91dGlsL01hcDt4cHNyACFkYXRhc3RydWN0dXJlcy5MaW5rZWRBcnJheUhhc2hNYXAAAAAAAAAAAQMAAkYACmxvYWRGYWN0b3JMAAloYXNoVGFibGV0AC5MZGF0YXN0cnVjdHVyZXMvYXJyYXloYXNodGFibGUvQXJyYXlIYXNoVGFibGU7eHB3CAAAAAFAAAAAc3IAFGphdmEubWF0aC5CaWdJbnRlZ2VyjPyfH6k7+x0DAAZJAAhiaXRDb3VudEkACWJpdExlbmd0aEkAE2ZpcnN0Tm9uemVyb0J5dGVOdW1JAAxsb3dlc3RTZXRCaXRJAAZzaWdudW1bAAltYWduaXR1ZGV0AAJbQnhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cP///////////////v////4AAAABdXIAAltCrPMX+AYIVOACAAB4cAAAABDORgSgAAAAAAAAAAAAAAABeHNxAH4AA3cIAAAAAEAAAAB4eA=="}
		AnnotationCmd:44 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"rule parameters setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":0}
		AnnotationCmd:45 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Setup"}}
		AnnotationCmd:46 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"multi contract setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":2}
		AnnotationCmd:47 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"contract address vars initialized"}}
		AssignExpCmd CANON:43 R8:48
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":3}
		AnnotationCmd:49 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"setup read tracking instrumentation"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":4}
		AnnotationCmd:50 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"last storage initialize"}}
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":5}
		AnnotationCmd:51 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"}}
		AssignExpCmd R10:13 Select(tacExtcodesize!!2:4 Apply(to_skey:bif R8:48) )
		AssumeExpCmd Ge(R10:13 0x1 )
		AssumeExpCmd Eq(R10:52 R1:53 )
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":6}
		AnnotationCmd:54 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming address(0).code has no code deployed"}}
		AssignExpCmd B12:13 Eq(Select(tacExtcodesize!!2:4 Apply(skey_basic:bif 0x0) ) 0x0 )
		AssumeCmd B12:13 "expToAssumeCmd"
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":7}
		AnnotationCmd:55 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":8}
		AnnotationCmd:56 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about static addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":9}
		AnnotationCmd:57 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish addresses of precompiled contracts"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":10}
		AnnotationCmd:58 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about uniqueness of contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":11}
		AnnotationCmd:59 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"static links"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":12}
		AnnotationCmd:60 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"record starting nonces"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":13}
		AnnotationCmd:61 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"cloned contracts have no balances"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":14}
		AnnotationCmd:62 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Linked immutable setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":15}
		AnnotationCmd:63 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Constrain immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":16}
		AnnotationCmd:64 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish equivalence of extension and base contract immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":17}
		AnnotationCmd:65 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Assuming all requireInvariants commands globally"}}
		AnnotationCmd JSON{"key":{"name":"cvl.assume.invariant.target","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":18}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1}
		AnnotationCmd:66 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"id":"arr","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd CANON4:36 0x3
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"arr151"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.ArrayLength"}],{"namePrefix":"CANON4","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}}}},{"key":{"name":"cvl.lengthof","type":"java.lang.String","erasureStrategy":"Canonical"},"value":"arr"},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"arr.length"}]},[{"#class":"tac.DataField.ArrayData"}],{"namePrefix":"CANON5","tag":{"#class":"tac.Tag.ByteMap"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":12,"charByteOffset":4},"end":{"line":12,"charByteOffset":19}}}},{"key":{"name":"cvl.data.is-reference","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":false},{"key":{"name":"cvl.dataof","type":"java.lang.String","erasureStrategy":"Canonical"},"value":"arr"},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"arr"}]}]}}
		AnnotationCmd:67 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":19}
		AnnotationCmd:68 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"id":"e","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"e152"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"coinbase"}],{"namePrefix":"CANON11","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.coinbase"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"basefee"}],{"namePrefix":"CANON9","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.basefee"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"timestamp"}],{"namePrefix":"CANON15","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.timestamp"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"blobbasefee"}],{"namePrefix":"CANON10","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.blobbasefee"}]},[{"#class":"tac.DataField.StructField","field":"tx"},{"#class":"tac.DataField.StructField","field":"origin"}],{"namePrefix":"CANON8","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.tx.origin"}]},[{"#class":"tac.DataField.StructField","field":"msg"},{"#class":"tac.DataField.StructField","field":"sender"}],{"namePrefix":"CANON6","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.msg.sender"}]},[{"#class":"tac.DataField.StructField","field":"msg"},{"#class":"tac.DataField.StructField","field":"value"}],{"namePrefix":"CANON7","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.msg.value"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"difficulty"}],{"namePrefix":"CANON12","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.difficulty"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"gaslimit"}],{"namePrefix":"CANON13","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.gaslimit"}]},[{"#class":"tac.DataField.StructField","field":"block"},{"#class":"tac.DataField.StructField","field":"number"}],{"namePrefix":"CANON14","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"fields":[{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":13,"charByteOffset":4},"end":{"line":13,"charByteOffset":10}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"e.block.number"}]}]}}
		AnnotationCmd:69 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":20}
		AnnotationCmd:70 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Definition","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":48}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"idL":[{"#class":"spec.cvlast.CVLLhs.Id","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":18}},"id":"extSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":4},"end":{"line":14,"charByteOffset":18}}}}],"exp":{"#class":"spec.cvlast.CVLExp.ApplyExp.ContractFunction.Concrete","methodIdWithCallContext":{"#class":"spec.cvlast.ConcreteMethod","signature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"TestArray"},"methodId":"uint256StaticArray"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"arr","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"range":{"#class":"utils.Range.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"range":{"#class":"utils.Range.Empty"}}]},"sighashInt":{"n":"5b31ae8e"}}},"args":[{"#class":"spec.cvlast.CVLExp.VariableExp","id":"e","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"env","fields":[{"fieldName":"msg","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"msg","fields":[{"fieldName":"sender","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"value","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"fieldName":"tx","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"tx","fields":[{"fieldName":"origin","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"fieldName":"block","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"block","fields":[{"fieldName":"basefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"blobbasefee","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"coinbase","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"difficulty","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"gaslimit","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"number","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"timestamp","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}}]},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":40},"end":{"line":14,"charByteOffset":41}}},"twoStateIndex":"NEITHER"},{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":43},"end":{"line":14,"charByteOffset":46}}},"twoStateIndex":"NEITHER"}],"noRevert":true,"storage":{"id":"lastStorage","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"},"range":{"#class":"utils.Range.Empty","comment":"empty storage type"}},"twoStateIndex":"NEITHER"},"isWhole":false,"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.VM","descriptor":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"context":{"#class":"spec.cvlast.typedescriptors.FromVMContext.ReturnValue"}},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":14,"charByteOffset":21},"end":{"line":14,"charByteOffset":47}},"annotation":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.CallResolution$DirectPassing","target":{"methodSignature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"TestArray"},"methodId":"uint256StaticArray"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"arr","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"range":{"#class":"utils.Range.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$UIntK","bitwidth":256},"range":{"#class":"utils.Range.Empty"}}]},"sighashInt":{"n":"5b31ae8e"}},"definitelyNonPayable":true,"annotation":{"visibility":"EXTERNAL","envFree":false,"library":false,"virtual":false},"stateMutability":"pure","evmExternalMethodInfo":{"sigHash":"5b31ae8e","name":"uint256StaticArray","argTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"}],"resultTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}],"stateMutability":"pure","isConstant":false,"paramNames":["arr"],"isLibrary":false,"contractName":"TestArray","contractInstanceId":"ce4604a0000000000000000000000001","sourceSegment":{"range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}},"content":"function uint256StaticArray(uint256[3] calldata arr) external pure returns (uint256) {\n        return arr[0] + arr[1] + arr[2];\n    }"}}},"hasEnv":true}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"certoraArg162163"},"src":{"id":"e152"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"certoraArg168169"},"src":{"id":"arr151"}}}
		AssignExpCmd R14:18 0x3
		AssignExpCmd M15:21 CANON5havocme656:42
	}
	Block 1_0_0_1_0_0 Succ [2_0_0_0_0_0] {
		AssignHavocCmd:71 CANON19!!16:33
		AnnotationCmd:71 JSON{"key":{"name":"call.trace.push","type":"analysis.icfg.Inliner$CallStack$PushRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"5b31ae8e"},"attr":{"#class":"scene.MethodAttribute.Common"}},"summary":null,"convention":{"#class":"analysis.icfg.Inliner.CallConventionType.FromCVL"},"calleeId":1}}
		AssignHavocCmd:71 tacCalldatasize!!17:5
		AssumeExpCmd Eq(tacCalldatasize!!17:5 0x64 )
		AssignExpCmd:71 tacCalldatabuf!!18@1:72 MapDefinition(CANON20.657:bv256 -> Ite(Lt(CANON20.657 tacCalldatasize!!17:5 ) Select(Select(Select(CANON21!4:26 CANON20.657 ) tacCalldatasize!!17:5 ) 0x5b31ae8e ) 0x0 ) bytemap)
		AssignExpCmd:71 R19:13 Select(Select(Select(CANON21!4:26 0x0 ) 0x64 ) 0x5b31ae8e )
		AssumeExpCmd LAnd(Ge(R19:13 0x5b31ae8e00000000000000000000000000000000000000000000000000000000 ) Le(R19:13 0x5b31ae8effffffffffffffffffffffffffffffffffffffffffffffffffffffff ) )
		AnnotationCmd:71 JSON{"key":{"name":"cvl.arg-serialization.start","type":"spec.CVLInvocationCompiler$StartSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		LabelCmd:71 "7: Read static array from certoraArg168169:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:71 "2: Read element at 0 from certoraArg168169:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:71 "1: Moving primitive..."
		AssignExpCmd R20:13 Select(CANON5havocme656:42 0x0 )
		AssignExpCmd:71 tacCalldatabuf!!21@1:72 Store(tacCalldatabuf!!18@1:72 0x4 R20:13 )
		LabelCmd:71 "...done 1"
		LabelCmd:71 "...done 2"
		LabelCmd:71 "4: Read element at 1 from certoraArg168169:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:71 "3: Moving primitive..."
		AssignExpCmd R22:13 Select(CANON5havocme656:42 0x20 )
		AssignExpCmd:71 tacCalldatabuf!!23@1:72 Store(tacCalldatabuf!!21@1:72 0x24 R22:13 )
		LabelCmd:71 "...done 3"
		LabelCmd:71 "...done 4"
		LabelCmd:71 "6: Read element at 2 from certoraArg168169:UserArray(elementType=Primitive(enc=Unsigned), isPacked=false)..."
		LabelCmd:71 "5: Moving primitive..."
		AssignExpCmd R24:13 Select(CANON5havocme656:42 0x40 )
		AssignExpCmd:71 tacCalldatabuf!!25@1:72 Store(tacCalldatabuf!!23@1:72 0x44 R24:13 )
		LabelCmd:71 "...done 5"
		LabelCmd:71 "...done 6"
		LabelCmd:71 "...done 7"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.external","type":"spec.CVLCompiler$Companion$TraceMeta$ExternalArg","erasureStrategy":"Erased"},"value":{"s":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"certoraArg168169"},"rootOffset":"0","callId":1,"targetType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$StaticArrayDescriptor","location":"calldata","elementType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"numElements":"3"},"sourceType":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"fields":[[{"#class":"tac.DataField.ArrayLength"}],{"namePrefix":"R14","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]},[{"#class":"tac.DataField.ArrayData"}],{"namePrefix":"M15","tag":{"#class":"tac.Tag.ByteMap"},"callIndex":0,"meta":[{"key":{"name":"cvl.data.is-reference","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":false},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"}}]}]}}
		AnnotationCmd:71 JSON{"key":{"name":"cvl.arg-serialization.end","type":"spec.CVLInvocationCompiler$EndSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		AssignExpCmd:71 R27:13 Apply(safe_math_narrow_bv256:bif CANON6:38)
		AssignExpCmd:71 R29:13 Select(tacBalance!!3:8 Apply(to_skey:bif R27:13) )
		AssignExpCmd:73 tacBalance!!31:8 Store(tacBalance!!3:8 Apply(to_skey:bif R27:13) R29:13 )
		AssignExpCmd:71 R32:13 Select(tacBalance!!31:8 Apply(to_skey:bif R8:48) )
		AssignExpCmd:73 R34:13 Apply(safe_math_narrow_bv256:bif R32:13)
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.TransferSnippet","srcAccountInfo":{"old":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R29","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"new":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R29","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"addr":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R27","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]}},"trgAccountInfo":{"old":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R32","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"new":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R34","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"addr":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R8","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"tacContractAt"}},{"key":{"name":"tac.contract.sym.addr.name","type":"java.lang.String","erasureStrategy":"Erased"},"value":"TestArray"},{"key":{"name":"tac.contract.sym.addr","type":"java.math.BigInteger","erasureStrategy":"Erased"},"value":"ce4604a0000000000000000000000001"}]}},"transferredAmount":{"#class":"vc.data.TACSymbol.Const","value":"0"}}}
		LabelCmd:71 "Start procedure TestArray-uint256StaticArray(uint256[3])"
		AnnotationCmd:71 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AssignExpCmd:71 R35:13 Select(tacExtcodesize!!2:4 Apply(to_skey:bif R8:74) )
		AssumeExpCmd Ge(R35:13 0x1 )
		AnnotationCmd:71 JSON{"key":{"name":"internal.func.finder.info","type":"analysis.ip.InternalFunctionFinderReport","erasureStrategy":"Erased"},"value":{"unresolvedFunctions":[]}}
		AnnotationCmd:71 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":0,"bytecodeCount":8,"sources":[{"source":0,"begin":61,"end":342}]}}
		LabelCmd "→ Assuming FP is strictly monotonic increasing"
		LabelCmd "←"
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":0,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":3,"charByteOffset":0},"end":{"line":11,"charByteOffset":1}},"content":"contract TestArray {...}"}}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":0}}
		AnnotationCmd:75 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":16,"bytecodeCount":7,"sources":[{"source":0,"begin":61,"end":342}]}}
		AnnotationCmd:76 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":26,"bytecodeCount":9,"sources":[{"source":0,"begin":61,"end":342}]}}
		AnnotationCmd:77 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":59,"bytecodeCount":14,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":552,"bytecodeCount":10,"sources":[{"source":1,"begin":603,"end":915},{"source":1,"begin":736,"end":738},{"source":1,"begin":724,"end":733},{"source":1,"begin":715,"end":722},{"source":1,"begin":711,"end":734},{"source":1,"begin":707,"end":739},{"source":1,"begin":704,"end":706}]}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":1,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}},"content":"compiler-generate condition in function uint256StaticArray(uint256[3] calldata arr) external pure returns (uint256) "}}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":1}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":570,"bytecodeCount":9,"sources":[{"source":1,"begin":704,"end":706},{"source":1,"begin":795,"end":796},{"source":1,"begin":820,"end":898},{"source":1,"begin":890,"end":897},{"source":1,"begin":881,"end":887},{"source":1,"begin":870,"end":879},{"source":1,"begin":866,"end":888}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":448,"bytecodeCount":15,"sources":[{"source":1,"begin":25,"end":207},{"source":1,"begin":127,"end":133},{"source":1,"begin":115,"end":133},{"source":1,"begin":180,"end":183},{"source":1,"begin":172,"end":176},{"source":1,"begin":166,"end":170},{"source":1,"begin":162,"end":177},{"source":1,"begin":152,"end":160},{"source":1,"begin":148,"end":178},{"source":1,"begin":145,"end":184},{"source":1,"begin":142,"end":144}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":472,"bytecodeCount":6,"sources":[{"source":1,"begin":142,"end":144},{"source":1,"begin":105,"end":207}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":584,"bytecodeCount":9,"sources":[{"source":1,"begin":820,"end":898},{"source":1,"begin":810,"end":898},{"source":1,"begin":766,"end":908},{"source":1,"begin":694,"end":915}]}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":80,"bytecodeCount":3,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:79 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":155,"bytecodeCount":9,"sources":[{"source":0,"begin":207,"end":340},{"source":0,"begin":283,"end":290},{"source":0,"begin":327,"end":330},{"source":0,"begin":331,"end":332},{"source":0,"begin":327,"end":333}]}}
		AnnotationCmd:80 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":215,"bytecodeCount":12,"sources":[{"source":0,"begin":327,"end":333},{"source":0,"begin":318,"end":321},{"source":0,"begin":322,"end":323},{"source":0,"begin":318,"end":324}]}}
		AnnotationCmd:81 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":278,"bytecodeCount":12,"sources":[{"source":0,"begin":318,"end":324},{"source":0,"begin":309,"end":312},{"source":0,"begin":313,"end":314},{"source":0,"begin":309,"end":315}]}}
		AnnotationCmd:82 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":341,"bytecodeCount":10,"sources":[{"source":0,"begin":309,"end":315},{"source":0,"begin":309,"end":324}]}}
		AnnotationCmd:83 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":704,"bytecodeCount":6,"sources":[{"source":1,"begin":1704,"end":2009},{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1781,"end":1782}]}}
		AnnotationCmd:83 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:83 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":715,"bytecodeCount":7,"sources":[{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1758,"end":1783},{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1815,"end":1816}]}}
		AnnotationCmd:83 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:83 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":726,"bytecodeCount":11,"sources":[{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1792,"end":1817},{"source":1,"begin":1951,"end":1952},{"source":1,"begin":1883,"end":1949},{"source":1,"begin":1879,"end":1953},{"source":1,"begin":1876,"end":1877},{"source":1,"begin":1873,"end":1954},{"source":1,"begin":1870,"end":1872}]}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":2,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":9,"charByteOffset":15},"end":{"line":9,"charByteOffset":30}},"content":"arr[0] + arr[1]"}}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":2}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":779,"bytecodeCount":11,"sources":[{"source":1,"begin":1870,"end":1872},{"source":1,"begin":2001,"end":2002},{"source":1,"begin":1998,"end":1999},{"source":1,"begin":1994,"end":2003},{"source":1,"begin":1987,"end":2003},{"source":1,"begin":1748,"end":2009}]}}
		AssumeExpCmd Apply(add_noofl_256:bif R20:13 R22:13)
		AssignExpCmd:84 R36:22 Apply(safe_math_narrow_bv256:bif IntAdd(R20:13 R22:13))
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":356,"bytecodeCount":6,"sources":[{"source":0,"begin":309,"end":324},{"source":0,"begin":309,"end":333}]}}
		AnnotationCmd:85 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":704,"bytecodeCount":6,"sources":[{"source":1,"begin":1704,"end":2009},{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1781,"end":1782}]}}
		AnnotationCmd:85 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:85 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":715,"bytecodeCount":7,"sources":[{"source":1,"begin":1763,"end":1783},{"source":1,"begin":1758,"end":1783},{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1815,"end":1816}]}}
		AnnotationCmd:85 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:85 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":726,"bytecodeCount":11,"sources":[{"source":1,"begin":1797,"end":1817},{"source":1,"begin":1792,"end":1817},{"source":1,"begin":1951,"end":1952},{"source":1,"begin":1883,"end":1949},{"source":1,"begin":1879,"end":1953},{"source":1,"begin":1876,"end":1877},{"source":1,"begin":1873,"end":1954},{"source":1,"begin":1870,"end":1872}]}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":3,"branchSource":{"range":{"specFile":"TestArray.sol","start":{"line":9,"charByteOffset":15},"end":{"line":9,"charByteOffset":39}},"content":"arr[0] + arr[1] + arr[2]"}}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":3}}
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":779,"bytecodeCount":11,"sources":[{"source":1,"begin":1870,"end":1872},{"source":1,"begin":2001,"end":2002},{"source":1,"begin":1998,"end":1999},{"source":1,"begin":1994,"end":2003},{"source":1,"begin":1987,"end":2003},{"source":1,"begin":1748,"end":2009}]}}
		AssumeExpCmd Apply(add_noofl_256:bif R36:22 R24:13)
		AssignExpCmd:84 R37:23 Apply(safe_math_narrow_bv256:bif IntAdd(R36:22 R24:13))
		AnnotationCmd:71 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":366,"bytecodeCount":7,"sources":[{"source":0,"begin":309,"end":333},{"source":0,"begin":302,"end":333},{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:86 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":85,"bytecodeCount":8,"sources":[{"source":0,"begin":207,"end":340}]}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":677,"bytecodeCount":14,"sources":[{"source":1,"begin":1476,"end":1698},{"source":1,"begin":1607,"end":1609},{"source":1,"begin":1596,"end":1605},{"source":1,"begin":1592,"end":1610},{"source":1,"begin":1584,"end":1610},{"source":1,"begin":1620,"end":1691},{"source":1,"begin":1688,"end":1689},{"source":1,"begin":1677,"end":1686},{"source":1,"begin":1673,"end":1690},{"source":1,"begin":1664,"end":1670}]}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":662,"bytecodeCount":5,"sources":[{"source":1,"begin":1352,"end":1470},{"source":1,"begin":1439,"end":1463},{"source":1,"begin":1457,"end":1462}]}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":790,"bytecodeCount":9,"sources":[{"source":1,"begin":2015,"end":2092},{"source":1,"begin":2081,"end":2086},{"source":1,"begin":2070,"end":2086},{"source":1,"begin":2060,"end":2092}]}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":671,"bytecodeCount":6,"sources":[{"source":1,"begin":1439,"end":1463},{"source":1,"begin":1434,"end":1437},{"source":1,"begin":1427,"end":1464},{"source":1,"begin":1417,"end":1470}]}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite","returnbufOffset":"0","returnValueSym":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R37","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1016}]},"callIndex":1}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":698,"bytecodeCount":6,"sources":[{"source":1,"begin":1620,"end":1691},{"source":1,"begin":1574,"end":1698}]}}
		AnnotationCmd:87 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"TestArray","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"7c71350e909902c4c0529d73ba8034a80d2b66fd32ceba334f42d836820906f2","pc":98,"bytecodeCount":8,"sources":[{"source":0,"begin":207,"end":340}]}}
		AssignExpCmd:88 lastReverted:3 false
		AnnotationCmd:88 JSON{"key":{"name":"tac.return.path","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd:71 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.HaltSnippet.Return","range":{"specFile":"TestArray.sol","start":{"line":8,"charByteOffset":4},"end":{"line":10,"charByteOffset":5}}}}
		LabelCmd:71 "End procedure TestArray-uint256StaticArray(uint256[3])"
		LabelCmd:71 "0: Move primitive value for variable extSum159:int..."
		AssignExpCmd:71 CANON19!!38:33 R37:23
		LabelCmd:71 "...done 0"
		AnnotationCmd:71 JSON{"key":{"name":"call.trace.pop","type":"analysis.icfg.Inliner$CallStack$PopRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"5b31ae8e"},"attr":{"#class":"scene.MethodAttribute.Common"}},"calleeId":1}}
	}
	Block 2_0_0_0_0_0 Succ [] {
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd:71 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":21}
		AnnotationCmd:89 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Definition","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":46}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"idL":[{"#class":"spec.cvlast.CVLLhs.Id","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":18}},"id":"cvlSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":18}}}}],"exp":{"#class":"spec.cvlast.CVLExp.BinaryExp.AddExp","l":{"#class":"spec.cvlast.CVLExp.BinaryExp.AddExp","l":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":24}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"0","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"0"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":25},"end":{"line":15,"charByteOffset":26}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":27}}}},"r":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":30},"end":{"line":15,"charByteOffset":33}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"1"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":34},"end":{"line":15,"charByteOffset":35}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":30},"end":{"line":15,"charByteOffset":36}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":36}}}},"r":{"#class":"spec.cvlast.CVLExp.ArrayDerefExp","array":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"arr","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.StaticArray","elementType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"logicalLength":"3"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":39},"end":{"line":15,"charByteOffset":42}}},"twoStateIndex":"NEITHER"},"index":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"2","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"2"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":43},"end":{"line":15,"charByteOffset":44}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":39},"end":{"line":15,"charByteOffset":45}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":45}}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp352353"},"src":{"id":"arr151"}}}
		AssignExpCmd:90 B40:14 false
		AssignExpCmd:91 R41:24 0x0
		LabelCmd:91 "12: Read dynamic array..."
		LabelCmd:91 "8: Read array length..."
		LabelCmd:91 "... done 8"
		LabelCmd:91 "11: Read element at tmp354355.356:bv256..."
		LabelCmd:91 "10: Moving primitive..."
		AssignExpCmd R42:13 Select(CANON5havocme656:42 0x0 )
		LabelCmd:91 "9: Move primitive value for variable tmp350351:int..."
		AssignExpCmd:91 I43:18 R42:13
		LabelCmd:91 "...done 9"
		LabelCmd:91 "...done 10"
		LabelCmd:91 "...done 11"
		LabelCmd:91 "...done 12"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I43","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R41","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"0"}}]}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp352353"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp365366"},"src":{"id":"arr151"}}}
		AssignExpCmd:92 B45:15 true
		AssignExpCmd:91 R46:25 0x1
		LabelCmd:91 "17: Read dynamic array..."
		LabelCmd:91 "13: Read array length..."
		LabelCmd:91 "... done 13"
		LabelCmd:91 "16: Read element at tmp367368.369:bv256..."
		LabelCmd:91 "15: Moving primitive..."
		AssignExpCmd R47:13 Select(CANON5havocme656:42 0x20 )
		LabelCmd:91 "14: Move primitive value for variable tmp363364:int..."
		AssignExpCmd:91 I48:18 R47:13
		LabelCmd:91 "...done 14"
		LabelCmd:91 "...done 15"
		LabelCmd:91 "...done 16"
		LabelCmd:91 "...done 17"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I48","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R46","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"1"}}]}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp365366"}}}
		AssignExpCmd:93 I49:19 IntAdd(R42:13 R47:13)
		AssumeExpCmd LAnd(Ge(I49:19 0x0(int) ) Le(I49:19 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp379380"},"src":{"id":"arr151"}}}
		AssignExpCmd:94 I51:20 0x2
		AssignExpCmd:91 R52:20 0x2
		LabelCmd:91 "22: Read dynamic array..."
		LabelCmd:91 "18: Read array length..."
		LabelCmd:91 "... done 18"
		LabelCmd:91 "21: Read element at tmp381382.383:bv256..."
		LabelCmd:91 "20: Moving primitive..."
		AssignExpCmd R53:13 Select(CANON5havocme656:42 0x40 )
		LabelCmd:91 "19: Move primitive value for variable tmp377378:int..."
		AssignExpCmd:91 I54:18 R53:13
		LabelCmd:91 "...done 19"
		LabelCmd:91 "...done 20"
		LabelCmd:91 "...done 21"
		LabelCmd:91 "...done 22"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I54","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$ArrayElem","i":{"namePrefix":"R52","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"2"}}]}}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp379380"}}}
		AssignExpCmd:95 CANON51:34 IntAdd(I49:19 R53:13)
		AssumeExpCmd LAnd(Ge(CANON51:34 0x0(int) ) Le(CANON51:34 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AnnotationCmd:91 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":22}
		AnnotationCmd:96 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Satisfy","range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":4},"end":{"line":16,"charByteOffset":29}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.EqExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"extSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":12},"end":{"line":16,"charByteOffset":18}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"cvlSum","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":22},"end":{"line":16,"charByteOffset":28}}},"twoStateIndex":"NEITHER"},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"TestArray.spec","start":{"line":16,"charByteOffset":12},"end":{"line":16,"charByteOffset":28}}}},"description":null,"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":1}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd:97 I55:19 CANON19!!38:33
		AssignExpCmd:98 I56:19 CANON51:34
		AssignExpCmd:99 CANON54:35 Eq(I55:19 I56:19 )
		AssumeCmd CANON54:35 "expToAssumeCmd"
		AssignExpCmd:100 B57:16 false
		AssertCmd:101 false "extSum == cvlSum"
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
    },
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "0"
      }
    }
  ],
  "15": [
    {
      "key": {
        "name": "tac.was.replaced.with.bool",
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
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "1"
      }
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
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
      }
    }
  ],
  "17": [
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
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
      }
    }
  ],
  "18": [
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
  "19": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
      }
    }
  ],
  "20": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "2"
      }
    }
  ],
  "21": [
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
    }
  ],
  "22": [
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
  "23": [
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
  "24": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "0"
      }
    }
  ],
  "25": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "1"
      }
    }
  ],
  "26": [
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
  "27": [
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
          "#class": "utils.Range.Range",
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
  "28": [
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
          "#class": "utils.Range.Range",
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
  "29": [
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
          "#class": "utils.Range.Range",
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
  "30": [
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
          "#class": "utils.Range.Range",
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
  "31": [
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
          "#class": "utils.Range.Range",
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
          "#class": "utils.Range.Range",
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
  "33": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "utils.Range.Range",
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
  "34": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "utils.Range.Range",
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
  "35": [
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
          "#class": "utils.Range.Range",
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
  "37": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "utils.Range.Range",
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
  "38": [
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
          "#class": "utils.Range.Range",
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
  "39": [
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
          "#class": "utils.Range.Range",
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
  "40": [
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
          "#class": "utils.Range.Range",
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
  "41": [
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
          "#class": "utils.Range.Range",
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
  "42": [
    {
      "key": {
        "name": "cvl.def.site",
        "type": "spec.CVLDefinitionSite",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.CVLDefinitionSite.Rule",
        "range": {
          "#class": "utils.Range.Range",
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
  "43": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract",
        "name": {
          "name": "TestArray"
        }
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
        "name": "tac.no.callindex",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "currentContract"
    }
  ],
  "44": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    }
  ],
  "45": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1
    }
  ],
  "46": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 2
    }
  ],
  "47": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 3
    }
  ],
  "48": [
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
    },
    {
      "key": {
        "name": "tac.non.zero.var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "49": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 4
    }
  ],
  "50": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 5
    }
  ],
  "51": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 6
    }
  ],
  "52": [
    {
      "key": {
        "name": "tac.is-temp-var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "tac.non.zero.var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "53": [
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
    },
    {
      "key": {
        "name": "tac.non.zero.var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "54": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 7
    }
  ],
  "55": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    }
  ],
  "56": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 9
    }
  ],
  "57": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 10
    }
  ],
  "58": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 11
    }
  ],
  "59": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 12
    }
  ],
  "60": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 13
    }
  ],
  "61": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 14
    }
  ],
  "62": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 15
    }
  ],
  "63": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 16
    }
  ],
  "64": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 17
    }
  ],
  "65": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 18
    }
  ],
  "66": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 19
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "67": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "68": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 20
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "69": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "70": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 21
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "71": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "72": [
    {
      "key": {
        "name": "tac.immutable.array",
        "type": "vc.data.ImmutableBound",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "sym": {
          "#class": "vc.data.TACSymbol.Var.Full",
          "namePrefix": "tacCalldatasize!!17",
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
  "73": [
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "74": [
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
    },
    {
      "key": {
        "name": "tac.non.zero.var",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    }
  ],
  "75": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 11,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "76": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 25,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "77": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 42,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "78": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 79,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "79": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 84,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "80": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 168,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "81": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 231,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "82": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 294,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "83": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 355,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "84": [
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
        "pc_": 782,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "85": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 365,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "86": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 372,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "87": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 97,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "88": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 106,
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
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "89": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 22
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "90": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "91": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "92": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "93": [
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
            "range": {
              "#class": "utils.Range.Range",
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
            "namePrefix": "R42",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
            "namePrefix": "R47",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "94": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "95": [
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
            "range": {
              "#class": "utils.Range.Range",
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
            "namePrefix": "I49",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "cvl.type",
                  "type": "spec.cvlast.CVLType$PureCVLType",
                  "erasureStrategy": "CallTrace"
                },
                "value": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
                }
              }
            ]
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                  "range": {
                    "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
            "namePrefix": "R53",
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
                "range": {
                  "#class": "utils.Range.Range",
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
                "range": {
                  "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "96": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 23
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "97": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "98": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "99": [
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
              "range": {
                "#class": "utils.Range.Range",
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
              "range": {
                "#class": "utils.Range.Range",
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
            "range": {
              "#class": "utils.Range.Range",
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
            "namePrefix": "I55",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "cvl.type",
                  "type": "spec.cvlast.CVLType$PureCVLType",
                  "erasureStrategy": "CallTrace"
                },
                "value": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
                }
              }
            ]
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
              "range": {
                "#class": "utils.Range.Range",
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
            "namePrefix": "I56",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "cvl.type",
                  "type": "spec.cvlast.CVLType$PureCVLType",
                  "erasureStrategy": "CallTrace"
                },
                "value": {
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Mathint"
                }
              }
            ]
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
              "range": {
                "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "100": [
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
            "range": {
              "#class": "utils.Range.Range",
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  "101": [
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
        "name": "satisfy.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    },
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
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
  ]
}