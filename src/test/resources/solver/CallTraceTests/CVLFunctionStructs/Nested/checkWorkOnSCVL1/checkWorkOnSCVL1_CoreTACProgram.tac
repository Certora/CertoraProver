TACSymbolTable {
	UserDefined {
		UninterpSort skey
	}
	BuiltinFunctions {
		to_skey:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.ToSkey"}
		skey_basic:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.Basic"}
	}
	UninterpretedFunctions {
	}
	tacCodesizeCANON0:bv256:0
	lastReverted:bool:1
	tacExtcodesize!!1:wordmap:2
	tacExtcodesize:wordmap:2
	B7:bool:3
	B8:bool:3
	I9:int:4
	R0:bv256:0
	R2:bv256:5
	R3:bv256:6
	R4:bv256:7
	R5:bv256:8
	R6:bv256:3
	B11:bool:9
	B18:bool:10
	B19:bool:9
	B22:bool:9
	B23:bool:9
	I10:int:11
	I12:int:4
	I13:int:12
	I14:int:13
	I15:int:14
	I16:int:15
	I17:int:16
	I20:int:4
	I21:int:11
	CANON10:int:17
	CANON11:int:18
	CANON12:int:19
	CANON13:int:20
	CANON14:int:21
	CANON15:int:22
	CANON16:int:23
	CANON17:int:24
	CANON18:int:25
	CANON19:bool:26
	CANON20:bool:27
	CANON21:bool:28
	CANON22:bool:29
	CANON23:bool:30
	CANON24:int:4
	CANON25:int:31
	CANON26:int:12
	CANON27:int:13
	CANON28:int:14
	CANON29:int:15
	CANON30:int:16
	CANON31:bool:10
	CANON32:int:32
	CANON33:int:33
	CANON34:int:34
	CANON35:int:35
	CANON36:bool:36
	CANON37:int:37
	CANON38:bool:38
	CANON39:bool:9
	CANON40:int:4
	CANON41:int:11
	CANON42:bool:9
	CANON43:bool:39
	CANON45:bool:9
	tacContractAtCANON1:bv256:5
	tacContractAtCANON2:bv256:6
	tacContractAtCANON3:bv256:7
	CANON1:int:40
	CANON2:int:41
	CANON3:bv256:3
	CANON4:bool:3
	CANON5:bool:3
	CANON6:int:4
	CANON7:int:11
	CANON8:bool:9
	CANON9:int:42
	tacContractAtCANON:bv256:8
	CANON:int:43
}
Program {
	Block 0_0_0_0_0_0 Succ [1_0_0_1_0_1] {
		AssignHavocCmd R0:0
		AssumeExpCmd Ge(R0:0 0x1 )
		AssignHavocCmd tacExtcodesize!!1:2
		AssignHavocCmd CANON10:17
		AssumeExpCmd LAnd(Ge(CANON10:17 0x0(int) ) Le(CANON10:17 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON11:18
		AssumeExpCmd LAnd(Ge(CANON11:18 0x0(int) ) Le(CANON11:18 0xff(int) ) )
		AssignHavocCmd CANON12:19
		AssumeExpCmd LAnd(Ge(CANON12:19 0x0(int) ) Le(CANON12:19 0xff(int) ) )
		AssignHavocCmd CANON13:20
		AssumeExpCmd LAnd(Ge(CANON13:20 0x0(int) ) Le(CANON13:20 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON14:21
		AssumeExpCmd LAnd(Ge(CANON14:21 0x0(int) ) Le(CANON14:21 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON15:22
		AssumeExpCmd LAnd(Ge(CANON15:22 0x0(int) ) Le(CANON15:22 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON16:23
		AssumeExpCmd LAnd(Ge(CANON16:23 0x0(int) ) Le(CANON16:23 0xff(int) ) )
		AssignHavocCmd CANON17:24
		AssumeExpCmd LAnd(Ge(CANON17:24 0x0(int) ) Le(CANON17:24 0xff(int) ) )
		AssignHavocCmd CANON18:25
		AssumeExpCmd LAnd(Ge(CANON18:25 0x0(int) ) Le(CANON18:25 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON19:26
		AssumeNotCmd CANON19:26
		AssignHavocCmd CANON20:27
		AssignHavocCmd CANON21:28
		AssignHavocCmd CANON22:29
		AssignHavocCmd CANON23:30
		AssignHavocCmd R2:5
		AssumeExpCmd Eq(R2:5 0x1 )
		AssignHavocCmd R3:6
		AssumeExpCmd Eq(R3:6 0x2 )
		AssignHavocCmd R4:7
		AssumeExpCmd Eq(R4:7 0x4 )
		AssignHavocCmd CANON9:42
		AssumeExpCmd LAnd(Ge(CANON9:42 0x0(int) ) Le(CANON9:42 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd R5:8
		AssumeExpCmd LAnd(Ge(R5:8 0x1 ) Le(R5:8 0xffffffffffffffffffffffffffffffffffffffff ) )
		AnnotationCmd JSON{"key":{"name":"tac.state.extension","type":"analysis.icfg.Inliner$ExtendedStateVars","erasureStrategy":"Canonical"},"value":"rO0ABXNyACdhbmFseXNpcy5pY2ZnLklubGluZXIkRXh0ZW5kZWRTdGF0ZVZhcnOvh/MjxNFkQAIAAUwAFmluc3RhbmNlVG9FeHRlbmRlZFZhcnN0AA9MamF2YS91dGlsL01hcDt4cHNyACFkYXRhc3RydWN0dXJlcy5MaW5rZWRBcnJheUhhc2hNYXAAAAAAAAAAAQMAAkYACmxvYWRGYWN0b3JMAAloYXNoVGFibGV0AC5MZGF0YXN0cnVjdHVyZXMvYXJyYXloYXNodGFibGUvQXJyYXlIYXNoVGFibGU7eHB3CAAAAAFAAAAAc3IAFGphdmEubWF0aC5CaWdJbnRlZ2VyjPyfH6k7+x0DAAZJAAhiaXRDb3VudEkACWJpdExlbmd0aEkAE2ZpcnN0Tm9uemVyb0J5dGVOdW1JAAxsb3dlc3RTZXRCaXRJAAZzaWdudW1bAAltYWduaXR1ZGV0AAJbQnhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cP///////////////v////4AAAABdXIAAltCrPMX+AYIVOACAAB4cAAAABDORgSgAAAAAAAAAAAAAAABeHNxAH4AA3cIAAAAAEAAAAB4eA=="}
		AnnotationCmd:44 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"rule parameters setup"}}
		AssignHavocCmd CANON:43
		AssumeExpCmd LAnd(Ge(CANON:43 0x0(int) ) Le(CANON:43 0x2(int) ) )
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.PrimitiveArg","callIndex":0,"index":0,"sym":{"namePrefix":"CANON","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":54,"charByteOffset":22},"end":{"line":54,"charByteOffset":28}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]},"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"id":"x","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":54,"charByteOffset":22},"end":{"line":54,"charByteOffset":28}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"CANON","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":54,"charByteOffset":22},"end":{"line":54,"charByteOffset":28}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]}},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Parameter","sourceName":"x"},"fields":null}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":0}
		AnnotationCmd:45 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Setup"}}
		AnnotationCmd:46 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"multi contract setup"}}
		AssignExpCmd CANON1:40 R5:47
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":2}
		AnnotationCmd:48 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"contract address vars initialized"}}
		AssignExpCmd CANON2:41 R5:47
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":3}
		AnnotationCmd:49 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"setup read tracking instrumentation"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":4}
		AnnotationCmd:50 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"last storage initialize"}}
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":5}
		AnnotationCmd:51 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"}}
		AssignExpCmd R6:3 Select(tacExtcodesize!!1:2 Apply(to_skey:bif R5:47) )
		AssumeExpCmd Ge(R6:3 0x1 )
		AssumeExpCmd Eq(R6:52 R0:53 )
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":6}
		AnnotationCmd:54 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming address(0).code has no code deployed"}}
		AssignExpCmd B8:3 Eq(Select(tacExtcodesize!!1:2 Apply(skey_basic:bif 0x0) ) 0x0 )
		AssumeCmd B8:3 "expToAssumeCmd"
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
		AnnotationCmd:66 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.AssumeCmd.Assume","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":55,"charByteOffset":4},"end":{"line":55,"charByteOffset":18}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.ArithRelopExp.LtExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":55,"charByteOffset":12},"end":{"line":55,"charByteOffset":13}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"3","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"3"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":55,"charByteOffset":16},"end":{"line":55,"charByteOffset":17}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":55,"charByteOffset":12},"end":{"line":55,"charByteOffset":17}}}},"description":null,"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"comesFromSpec":true}}}
		AssignExpCmd:67 I9:4 CANON:43
		AssignExpCmd:68 I10:11 0x3
		AssignExpCmd:69 B11:9 true
		AnnotationCmd:70 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":19}
		AnnotationCmd:71 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"id":"s","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"s875"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.StructField","field":"b3"}],{"namePrefix":"CANON20","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b3"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"x"}],{"namePrefix":"CANON9","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.x"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"y"}],{"namePrefix":"CANON10","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.y"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"x"}],{"namePrefix":"CANON14","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.x"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"y"}],{"namePrefix":"CANON15","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.y"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"b1"}],{"namePrefix":"CANON19","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.b1"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"b2"}],{"namePrefix":"CANON21","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.b2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"b1"}],{"namePrefix":"CANON22","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.b1"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"b2"}],{"namePrefix":"CANON23","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.b2"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"x2"}],{"namePrefix":"CANON13","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.x2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"x2"}],{"namePrefix":"CANON18","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.x2"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"z1"}],{"namePrefix":"CANON11","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.z1"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"z2"}],{"namePrefix":"CANON12","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.z2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"z1"}],{"namePrefix":"CANON16","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.z1"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"z2"}],{"namePrefix":"CANON17","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":56,"charByteOffset":4},"end":{"line":56,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.z2"}]}]}}
		AnnotationCmd:72 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":20}
		AnnotationCmd:73 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Apply","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":4},"end":{"line":57,"charByteOffset":24}},"exp":{"#class":"spec.cvlast.CVLExp.ApplyExp.CVLFunction","id":"workOnSCVL","args":[{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":15},"end":{"line":57,"charByteOffset":16}}},"twoStateIndex":"NEITHER"},{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":18},"end":{"line":57,"charByteOffset":19}}},"twoStateIndex":"NEITHER"},"fieldName":"s1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":18},"end":{"line":57,"charByteOffset":22}}}}],"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Void"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":4},"end":{"line":57,"charByteOffset":23}}},"noRevert":true},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
	}
	Block 1_0_0_1_0_1 Succ [2_0_0_0_0_0] {
		AnnotationCmd:74 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLFunctionStart","callIndex":1,"name":"workOnSCVL","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":57,"charByteOffset":4},"end":{"line":57,"charByteOffset":23}},"isNoRevert":true}}
		AssignExpCmd:75 I12:4 CANON:43
		AssignExpCmd:74 CANON25:31 I12:4
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.PrimitiveArg","callIndex":1,"index":0,"sym":{"namePrefix":"CANON25","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":20},"end":{"line":14,"charByteOffset":26}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]},"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"id":"x","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":20},"end":{"line":14,"charByteOffset":26}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp915916"},"src":{"id":"s875"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"s913914"},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"s1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp915916"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"s910"},"src":{"id":"s913914"}}}
		AssignExpCmd CANON32:32 CANON9:42
		AssignExpCmd CANON33:33 CANON10:17
		AssignExpCmd CANON34:34 CANON11:18
		AssignExpCmd CANON35:35 CANON12:19
		AssignExpCmd CANON36:36 false
		AssignExpCmd CANON37:37 CANON13:20
		AssignExpCmd CANON38:38 CANON21:28
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.StructArg","callIndex":1,"index":1,"symbols":[{"namePrefix":"CANON32","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.x"}]},{"namePrefix":"CANON33","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.y"}]},{"namePrefix":"CANON34","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.z1"}]},{"namePrefix":"CANON35","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.z2"}]},{"namePrefix":"CANON36","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b1"}]},{"namePrefix":"CANON37","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.x2"}]},{"namePrefix":"CANON38","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b2"}]}],"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"id":"s","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}}}
		AnnotationCmd:76 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.AssumeCmd.Assume","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":28}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.EqExp","l":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":13}}},"twoStateIndex":"NEITHER"},"fieldName":"b1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":16}}}},"r":{"#class":"spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":22}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"3","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"3"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":25},"end":{"line":15,"charByteOffset":26}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":26}},"hasParens":true}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":27}}}},"description":null,"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"comesFromSpec":true}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp925926"},"src":{"id":"s910"}}}
		AssignExpCmd:77 B19:9 false
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"B19","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"b1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp925926"}}}
		AssignExpCmd:78 I20:4 CANON25:31
		AssignExpCmd:79 I21:11 0x3
		AssignExpCmd:80 B22:9 false
		AssignExpCmd:81 CANON43:39 true
		AnnotationCmd:82 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":22}
		AssignExpCmd:74 lastReverted:1 false
		AnnotationCmd:74 JSON{"key":{"name":"tac.return.path","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd:74 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLFunctionEnd","callIndex":1,"name":"workOnSCVL"}}
		LabelCmd:74 "join point of revert handling"
	}
	Block 2_0_0_0_0_0 Succ [] {
		AnnotationCmd:74 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":21}
		AnnotationCmd:83 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Assert","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":58,"charByteOffset":4},"end":{"line":58,"charByteOffset":19}},"exp":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":58,"charByteOffset":11},"end":{"line":58,"charByteOffset":12}}},"twoStateIndex":"NEITHER"},"fieldName":"s1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":58,"charByteOffset":11},"end":{"line":58,"charByteOffset":15}}}},"fieldName":"b1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":58,"charByteOffset":11},"end":{"line":58,"charByteOffset":18}}}},"description":null,"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":6}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp970971"},"src":{"id":"s875"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp968969"},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"s1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp970971"}}}
		AssignExpCmd:84 B23:9 false
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"B23","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"b1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp968969"}}}
		AssertCmd:85 false "s.s1.b1"
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
  "1": [
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
  "2": [
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
  "3": [
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
  "4": [
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
  "5": [
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
  "6": [
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
  "7": [
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
  "8": [
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
      "value": "TestContract"
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
  "9": [
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
  "10": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "b2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
      }
    }
  ],
  "11": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
        "n": "3"
      }
    }
  ],
  "12": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x",
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
  "13": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "y",
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
  "14": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 8
      }
    }
  ],
  "15": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
        "k": 8
      }
    }
  ],
  "16": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x2",
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
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "y",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s1.y"
    }
  ],
  "18": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
        "k": 8
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
      "value": "s.s1.z1"
    }
  ],
  "19": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
        "k": 8
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
      "value": "s.s1.z2"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x2",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s1.x2"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s2.x"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "y",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s2.y"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
        "k": 8
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
      "value": "s.s2.z1"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "z2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
        "k": 8
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
      "value": "s.s2.z2"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x2",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s2.x2"
    }
  ],
  "26": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "b1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s1.b1"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "b3",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.b3"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "b2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s1.b2"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "b1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s2.b1"
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
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "b2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s2.b2"
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 20
          },
          "end": {
            "line": 14,
            "charByteOffset": 26
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
      "value": "x"
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
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "x",
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
      "value": "s.x"
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
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "y",
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
      "value": "s.y"
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
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "z1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
        "k": 8
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
      "value": "s.z1"
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
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "z2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
              "k": 8
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
        "k": 8
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
      "value": "s.z2"
    }
  ],
  "36": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "b1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
      "value": "s.b1"
    }
  ],
  "37": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "x2",
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
      "value": "s.x2"
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
          "name": "TestContract.Simple",
          "fields": [
            {
              "fieldName": "x",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "y",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
              }
            },
            {
              "fieldName": "z1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "z2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 8
              }
            },
            {
              "fieldName": "b1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            },
            {
              "fieldName": "x2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            },
            {
              "fieldName": "b2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "b2",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
        "#class": "spec.CVLDefinitionSite.Function",
        "range": {
          "#class": "utils.Range.Range",
          "specFile": "test.spec",
          "start": {
            "line": 14,
            "charByteOffset": 28
          },
          "end": {
            "line": 14,
            "charByteOffset": 49
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
      "value": "s.b2"
    }
  ],
  "39": [
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
  "40": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract",
        "name": {
          "name": "TestContract"
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
      "value": "testContract"
    }
  ],
  "41": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract",
        "name": {
          "name": "TestContract"
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
  "42": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "TestContract.Complex",
          "fields": [
            {
              "fieldName": "s1",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "s2",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              }
            },
            {
              "fieldName": "b3",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "s1",
            "cvlType": {
              "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
              "name": "TestContract.Simple",
              "fields": [
                {
                  "fieldName": "x",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "y",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                  }
                },
                {
                  "fieldName": "z1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "z2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 8
                  }
                },
                {
                  "fieldName": "b1",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                },
                {
                  "fieldName": "x2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                    "k": 256
                  }
                },
                {
                  "fieldName": "b2",
                  "cvlType": {
                    "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                  }
                }
              ]
            }
          },
          {
            "fieldName": "x",
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
          "specFile": "test.spec",
          "start": {
            "line": 56,
            "charByteOffset": 4
          },
          "end": {
            "line": 56,
            "charByteOffset": 27
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
      "value": "s.s1.x"
    }
  ],
  "43": [
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
          "specFile": "test.spec",
          "start": {
            "line": 54,
            "charByteOffset": 22
          },
          "end": {
            "line": 54,
            "charByteOffset": 28
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
      "value": "x"
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
      "value": "TestContract"
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
  "48": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 3
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
        "specFile": "test.spec",
        "start": {
          "line": 55,
          "charByteOffset": 4
        },
        "end": {
          "line": 55,
          "charByteOffset": 18
        }
      }
    }
  ],
  "67": [
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
          "id": "x",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 6
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
              "specFile": "test.spec",
              "start": {
                "line": 55,
                "charByteOffset": 12
              },
              "end": {
                "line": 55,
                "charByteOffset": 13
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
        "specFile": "test.spec",
        "start": {
          "line": 55,
          "charByteOffset": 4
        },
        "end": {
          "line": 55,
          "charByteOffset": 18
        }
      }
    }
  ],
  "68": [
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
          "n": "3",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 6
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
              "n": "3"
            },
            "range": {
              "#class": "utils.Range.Range",
              "specFile": "test.spec",
              "start": {
                "line": 55,
                "charByteOffset": 16
              },
              "end": {
                "line": 55,
                "charByteOffset": 17
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
        "specFile": "test.spec",
        "start": {
          "line": 55,
          "charByteOffset": 4
        },
        "end": {
          "line": 55,
          "charByteOffset": 18
        }
      }
    }
  ],
  "69": [
    {
      "key": {
        "name": "cvl.exp",
        "type": "spec.CVLExpToTACExprMeta",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.CVLExpToTACExprMeta.BinaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.RelopExp.ArithRelopExp.LtExp",
          "l": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "x",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 6
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
                "specFile": "test.spec",
                "start": {
                  "line": 55,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 55,
                  "charByteOffset": 13
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
            "n": "3",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 6
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
                "n": "3"
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
                "start": {
                  "line": 55,
                  "charByteOffset": 16
                },
                "end": {
                  "line": 55,
                  "charByteOffset": 17
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
                  "scopeId": 6
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
              "specFile": "test.spec",
              "start": {
                "line": 55,
                "charByteOffset": 12
              },
              "end": {
                "line": 55,
                "charByteOffset": 17
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I9",
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "x",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 6
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
                "specFile": "test.spec",
                "start": {
                  "line": 55,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 55,
                  "charByteOffset": 13
                }
              }
            },
            "twoStateIndex": "NEITHER"
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Const",
            "value": "3"
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
            "n": "3",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 6
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
                "n": "3"
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
                "start": {
                  "line": 55,
                  "charByteOffset": 16
                },
                "end": {
                  "line": 55,
                  "charByteOffset": 17
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
        "specFile": "test.spec",
        "start": {
          "line": 55,
          "charByteOffset": 4
        },
        "end": {
          "line": 55,
          "charByteOffset": 18
        }
      }
    }
  ],
  "70": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "test.spec",
        "start": {
          "line": 55,
          "charByteOffset": 4
        },
        "end": {
          "line": 55,
          "charByteOffset": 18
        }
      }
    }
  ],
  "71": [
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
        "specFile": "test.spec",
        "start": {
          "line": 56,
          "charByteOffset": 4
        },
        "end": {
          "line": 56,
          "charByteOffset": 27
        }
      }
    }
  ],
  "72": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "test.spec",
        "start": {
          "line": 56,
          "charByteOffset": 4
        },
        "end": {
          "line": 56,
          "charByteOffset": 27
        }
      }
    }
  ],
  "73": [
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
        "specFile": "test.spec",
        "start": {
          "line": 57,
          "charByteOffset": 4
        },
        "end": {
          "line": 57,
          "charByteOffset": 24
        }
      }
    }
  ],
  "74": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "test.spec",
        "start": {
          "line": 57,
          "charByteOffset": 4
        },
        "end": {
          "line": 57,
          "charByteOffset": 24
        }
      }
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
          "#class": "spec.cvlast.CVLExp.VariableExp",
          "id": "x",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 6
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
              "specFile": "test.spec",
              "start": {
                "line": 57,
                "charByteOffset": 15
              },
              "end": {
                "line": 57,
                "charByteOffset": 16
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
        "specFile": "test.spec",
        "start": {
          "line": 57,
          "charByteOffset": 4
        },
        "end": {
          "line": 57,
          "charByteOffset": 24
        }
      }
    }
  ],
  "76": [
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
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
          "#class": "spec.cvlast.CVLExp.FieldSelectExp",
          "structExp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "s",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 13
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "fieldName": "b1",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                  "scopeId": 10
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
              "specFile": "test.spec",
              "start": {
                "line": 15,
                "charByteOffset": 12
              },
              "end": {
                "line": 15,
                "charByteOffset": 16
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
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
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.VariableExp",
          "id": "x",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                  "scopeId": 10
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
              "specFile": "test.spec",
              "start": {
                "line": 15,
                "charByteOffset": 21
              },
              "end": {
                "line": 15,
                "charByteOffset": 22
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
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
          "n": "3",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                  "scopeId": 10
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
              "n": "3"
            },
            "range": {
              "#class": "utils.Range.Range",
              "specFile": "test.spec",
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
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
          "#class": "spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp",
          "l": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "x",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 22
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
            "n": "3",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "n": "3"
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
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
                  "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                  "scopeId": 10
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
              "specFile": "test.spec",
              "start": {
                "line": 15,
                "charByteOffset": 21
              },
              "end": {
                "line": 15,
                "charByteOffset": 26
              }
            },
            "hasParens": true
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I20",
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                  "k": 256
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "x",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 22
                }
              }
            },
            "twoStateIndex": "NEITHER"
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Const",
            "value": "3"
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
            "n": "3",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "n": "3"
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
        }
      }
    }
  ],
  "81": [
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
            "#class": "spec.cvlast.CVLExp.FieldSelectExp",
            "structExp": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "s",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                  "name": "TestContract.Simple",
                  "fields": [
                    {
                      "fieldName": "x",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    },
                    {
                      "fieldName": "y",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                      }
                    },
                    {
                      "fieldName": "z1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 8
                      }
                    },
                    {
                      "fieldName": "z2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 8
                      }
                    },
                    {
                      "fieldName": "b1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                      }
                    },
                    {
                      "fieldName": "x2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    },
                    {
                      "fieldName": "b2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                      }
                    }
                  ]
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "test.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 12
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 13
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "b1",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 16
                }
              }
            }
          },
          "r": {
            "#class": "spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp",
            "l": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "x",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "specFile": "test.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 22
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "r": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "3",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "n": "3"
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "test.spec",
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
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 26
                }
              },
              "hasParens": true
            }
          },
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                  "scopeId": 10
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
              "specFile": "test.spec",
              "start": {
                "line": 15,
                "charByteOffset": 12
              },
              "end": {
                "line": 15,
                "charByteOffset": 27
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "CANON44",
            "tag": {
              "#class": "tac.Tag.Bool"
            },
            "callIndex": 0,
            "meta": [
              {
                "key": {
                  "name": "cvl.struct.path",
                  "type": "spec.cvlast.CVLStructPathNode",
                  "erasureStrategy": "CallTrace"
                },
                "value": {
                  "rootStructType": {
                    "name": "TestContract.Simple",
                    "fields": [
                      {
                        "fieldName": "x",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                          "k": 256
                        }
                      },
                      {
                        "fieldName": "y",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                        }
                      },
                      {
                        "fieldName": "z1",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                          "k": 8
                        }
                      },
                      {
                        "fieldName": "z2",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                          "k": 8
                        }
                      },
                      {
                        "fieldName": "b1",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                        }
                      },
                      {
                        "fieldName": "x2",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                          "k": 256
                        }
                      },
                      {
                        "fieldName": "b2",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                        }
                      }
                    ]
                  },
                  "fields": [
                    {
                      "fieldName": "b1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                }
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.FieldSelectExp",
            "structExp": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "s",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                  "name": "TestContract.Simple",
                  "fields": [
                    {
                      "fieldName": "x",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    },
                    {
                      "fieldName": "y",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                      }
                    },
                    {
                      "fieldName": "z1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 8
                      }
                    },
                    {
                      "fieldName": "z2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 8
                      }
                    },
                    {
                      "fieldName": "b1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                      }
                    },
                    {
                      "fieldName": "x2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    },
                    {
                      "fieldName": "b2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                      }
                    }
                  ]
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "test.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 12
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 13
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "b1",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 16
                }
              }
            }
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Const",
            "value": "0",
            "tag": {
              "#class": "tac.Tag.Bool"
            }
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp",
            "l": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "x",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "specFile": "test.spec",
                  "start": {
                    "line": 15,
                    "charByteOffset": 21
                  },
                  "end": {
                    "line": 15,
                    "charByteOffset": 22
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "r": {
              "#class": "spec.cvlast.CVLExp.Constant.NumberLit",
              "n": "3",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                      "scopeId": 10
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
                  "n": "3"
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "test.spec",
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
                    "#class": "spec.cvlast.CVLScope.Item.CVLFunctionScopeItem",
                    "scopeId": 10
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
                "specFile": "test.spec",
                "start": {
                  "line": 15,
                  "charByteOffset": 21
                },
                "end": {
                  "line": 15,
                  "charByteOffset": 26
                }
              },
              "hasParens": true
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
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
        }
      }
    }
  ],
  "82": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "test.spec",
        "start": {
          "line": 15,
          "charByteOffset": 4
        },
        "end": {
          "line": 15,
          "charByteOffset": 28
        }
      }
    }
  ],
  "83": [
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
        "specFile": "test.spec",
        "start": {
          "line": 58,
          "charByteOffset": 4
        },
        "end": {
          "line": 58,
          "charByteOffset": 19
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
        "#class": "spec.CVLExpToTACExprMeta.NullaryCVLExp",
        "exp": {
          "#class": "spec.cvlast.CVLExp.FieldSelectExp",
          "structExp": {
            "#class": "spec.cvlast.CVLExp.FieldSelectExp",
            "structExp": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "s",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 6
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                  "name": "TestContract.Complex",
                  "fields": [
                    {
                      "fieldName": "s1",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                        "name": "TestContract.Simple",
                        "fields": [
                          {
                            "fieldName": "x",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 256
                            }
                          },
                          {
                            "fieldName": "y",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                            }
                          },
                          {
                            "fieldName": "z1",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 8
                            }
                          },
                          {
                            "fieldName": "z2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 8
                            }
                          },
                          {
                            "fieldName": "b1",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                            }
                          },
                          {
                            "fieldName": "x2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 256
                            }
                          },
                          {
                            "fieldName": "b2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                            }
                          }
                        ]
                      }
                    },
                    {
                      "fieldName": "s2",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                        "name": "TestContract.Simple",
                        "fields": [
                          {
                            "fieldName": "x",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 256
                            }
                          },
                          {
                            "fieldName": "y",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                            }
                          },
                          {
                            "fieldName": "z1",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 8
                            }
                          },
                          {
                            "fieldName": "z2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 8
                            }
                          },
                          {
                            "fieldName": "b1",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                            }
                          },
                          {
                            "fieldName": "x2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                              "k": 256
                            }
                          },
                          {
                            "fieldName": "b2",
                            "cvlType": {
                              "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                            }
                          }
                        ]
                      }
                    },
                    {
                      "fieldName": "b3",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                      }
                    }
                  ]
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "test.spec",
                  "start": {
                    "line": 58,
                    "charByteOffset": 11
                  },
                  "end": {
                    "line": 58,
                    "charByteOffset": 12
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "s1",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 6
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
                "#class": "spec.cvlast.CVLType.PureCVLType.Struct",
                "name": "TestContract.Simple",
                "fields": [
                  {
                    "fieldName": "x",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "y",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"
                    }
                  },
                  {
                    "fieldName": "z1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "z2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 8
                    }
                  },
                  {
                    "fieldName": "b1",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  },
                  {
                    "fieldName": "x2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  },
                  {
                    "fieldName": "b2",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.Bool"
                    }
                  }
                ]
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "test.spec",
                "start": {
                  "line": 58,
                  "charByteOffset": 11
                },
                "end": {
                  "line": 58,
                  "charByteOffset": 15
                }
              }
            }
          },
          "fieldName": "b1",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 6
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
              "specFile": "test.spec",
              "start": {
                "line": 58,
                "charByteOffset": 11
              },
              "end": {
                "line": 58,
                "charByteOffset": 18
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
        "specFile": "test.spec",
        "start": {
          "line": 58,
          "charByteOffset": 4
        },
        "end": {
          "line": 58,
          "charByteOffset": 19
        }
      }
    }
  ],
  "85": [
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
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "test.spec",
        "start": {
          "line": 58,
          "charByteOffset": 4
        },
        "end": {
          "line": 58,
          "charByteOffset": 19
        }
      }
    }
  ]
}