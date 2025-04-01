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
	tacNonce:wordmap:1
	tacContractsCreated!!16:wordmap:2
	lastStorageCANON:wordmap:3
	CANON10!!5:wordmap:4
	CANON11!!6:wordmap:5
	CANON12!!7:wordmap:6
	CANON13!!8:wordmap:7
	CANON14!!9:wordmap:8
	tacExtcodesize!!3:wordmap:9
	CANON3!!17:wordmap:10
	CANON4!!18:wordmap:11
	tacSCANON0!!25:wordmap:12
	CANON5!!19:wordmap:13
	CANON6!!20:wordmap:14
	CANON7!!21:wordmap:15
	CANON8!!22:wordmap:16
	CANON9!!23:wordmap:17
	tacTCANON0:wordmap:18
	tacExtcodesize:wordmap:9
	tacBalance:wordmap:19
	tacBalance!!4:wordmap:19
	lastStorageCANON1:wordmap:3
	lastStorageCANON2:wordmap:20
	lastStorageCANON3:wordmap:21
	lastStorageCANON4:wordmap:22
	lastStorageCANON5:wordmap:23
	lastStorageCANON6:wordmap:24
	lastStorageCANON7:wordmap:25
	lastStorageCANON8:wordmap:25
	lastStorageCANON9:wordmap:25
	R0:bv256:0
	W2:wordmap:18
	B27:bool:26
	B28:bool:26
	B31:bool
	B38:bool
	B39:bool
	B42:bool
	B43:bool
	I29:int
	I30:int
	I32:int
	I33:int
	I34:int
	I35:int
	I36:int
	I37:int
	I40:int
	I41:int
	R13:bv256:27
	R14:bv256:28
	R15:bv256:29
	R24:bv256:30
	R26:bv256:26
	tacNonce!!1:wordmap:1
	CANON15!!10:wordmap:31
	CANON16!!11:wordmap:32
	CANON17!!12:wordmap:33
	CANON10:wordmap:4
	CANON11:wordmap:5
	CANON12:wordmap:6
	CANON13:wordmap:7
	CANON14:wordmap:8
	CANON15:wordmap:31
	CANON16:wordmap:32
	CANON17:wordmap:33
	CANON18:bv256:26
	CANON19:bool:26
	CANON20:bool:26
	CANON21:int
	CANON22:int
	CANON23:bool
	CANON24:int:34
	CANON25:int:35
	CANON26:int:36
	CANON27:int:37
	CANON28:int:38
	CANON29:int:39
	CANON30:int:40
	CANON31:int:41
	CANON32:int:42
	CANON33:int:43
	CANON34:bool:44
	CANON35:bool:45
	CANON36:bool:46
	CANON37:bool:47
	CANON38:bool:48
	CANON39:int
	CANON40:int:49
	CANON41:int
	CANON42:int
	CANON43:int
	CANON44:int
	CANON45:int
	CANON46:bool
	CANON47:int:50
	CANON48:int:51
	CANON49:int:52
	CANON50:int:53
	CANON51:bool:54
	CANON52:int:55
	CANON53:bool:56
	CANON54:bool
	CANON55:int
	CANON56:int
	CANON57:bool
	CANON58:bool:57
	CANON60:bool
	tacContractAtCANON1:bv256:27
	tacContractAtCANON2:bv256:28
	tacContractAtCANON3:bv256:29
	tacContractsCreated:wordmap:2
	CANON1:int:58
	CANON2:int:59
	CANON3:wordmap:10
	CANON4:wordmap:11
	CANON5:wordmap:13
	CANON6:wordmap:14
	CANON7:wordmap:15
	CANON8:wordmap:16
	CANON9:wordmap:17
	tacContractAtCANON:bv256:30
	tacSCANON0:wordmap:12
	lastStorageCANON10:wordmap:25
	lastStorageCANON11:wordmap:23
	lastStorageCANON12:wordmap:3
	lastStorageCANON13:wordmap:20
	lastStorageCANON14:wordmap:21
	lastStorageCANON15:wordmap:22
	lastStorageCANON16:wordmap:24
	lastStorageCANON17:wordmap:60
	lastStorageCANON18:wordmap:60
	lastStorageCANON19:wordmap:60
	lastStorageCANON20:wordmap:60
	CANON:int:61
}
Program {
	Block 0_0_0_0_0_0 Succ [1_0_0_1_0_1] {
		AssignHavocCmd R0:0
		AssumeExpCmd Ge(R0:0 0x1 )
		AssignHavocCmd tacExtcodesize!!3:9
		AssignHavocCmd CANON10!!5:4
		AssignHavocCmd CANON11!!6:5
		AssignHavocCmd CANON12!!7:6
		AssignHavocCmd CANON13!!8:7
		AssignHavocCmd CANON14!!9:8
		AssignHavocCmd CANON15!!10:31
		AssignHavocCmd CANON16!!11:32
		AssignHavocCmd CANON17!!12:33
		AssignHavocCmd CANON24:34
		AssumeExpCmd LAnd(Ge(CANON24:34 0x0(int) ) Le(CANON24:34 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON25:35
		AssumeExpCmd LAnd(Ge(CANON25:35 0x0(int) ) Le(CANON25:35 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON26:36
		AssumeExpCmd LAnd(Ge(CANON26:36 0x0(int) ) Le(CANON26:36 0xff(int) ) )
		AssignHavocCmd CANON27:37
		AssumeExpCmd LAnd(Ge(CANON27:37 0x0(int) ) Le(CANON27:37 0xff(int) ) )
		AssignHavocCmd CANON28:38
		AssumeExpCmd LAnd(Ge(CANON28:38 0x0(int) ) Le(CANON28:38 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON29:39
		AssumeExpCmd LAnd(Ge(CANON29:39 0x0(int) ) Le(CANON29:39 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON30:40
		AssumeExpCmd LAnd(Ge(CANON30:40 0x0(int) ) Le(CANON30:40 0xffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON31:41
		AssumeExpCmd LAnd(Ge(CANON31:41 0x0(int) ) Le(CANON31:41 0xff(int) ) )
		AssignHavocCmd CANON32:42
		AssumeExpCmd LAnd(Ge(CANON32:42 0x0(int) ) Le(CANON32:42 0xff(int) ) )
		AssignHavocCmd CANON33:43
		AssumeExpCmd LAnd(Ge(CANON33:43 0x0(int) ) Le(CANON33:43 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff(int) ) )
		AssignHavocCmd CANON34:44
		AssumeNotCmd CANON34:44
		AssignHavocCmd CANON35:45
		AssignHavocCmd CANON36:46
		AssignHavocCmd CANON37:47
		AssignHavocCmd CANON38:48
		AssignHavocCmd R13:27
		AssumeExpCmd Eq(R13:27 0x1 )
		AssignHavocCmd R14:28
		AssumeExpCmd Eq(R14:28 0x2 )
		AssignHavocCmd R15:29
		AssumeExpCmd Eq(R15:29 0x4 )
		AssignHavocCmd CANON3!!17:10
		AssignHavocCmd CANON4!!18:11
		AssignHavocCmd CANON5!!19:13
		AssignHavocCmd CANON6!!20:14
		AssignHavocCmd CANON7!!21:15
		AssignHavocCmd CANON8!!22:16
		AssignHavocCmd CANON9!!23:17
		AssignHavocCmd R24:30
		AssumeExpCmd LAnd(Ge(R24:30 0x1 ) Le(R24:30 0xffffffffffffffffffffffffffffffffffffffff ) )
		AssignHavocCmd tacSCANON0!!25:12
		AnnotationCmd JSON{"key":{"name":"tac.state.extension","type":"analysis.icfg.Inliner$ExtendedStateVars","erasureStrategy":"Canonical"},"value":"rO0ABXNyACdhbmFseXNpcy5pY2ZnLklubGluZXIkRXh0ZW5kZWRTdGF0ZVZhcnOvh/MjxNFkQAIAAUwAFmluc3RhbmNlVG9FeHRlbmRlZFZhcnN0AA9MamF2YS91dGlsL01hcDt4cHNyACFkYXRhc3RydWN0dXJlcy5MaW5rZWRBcnJheUhhc2hNYXAAAAAAAAAAAQMAAkYACmxvYWRGYWN0b3JMAAloYXNoVGFibGV0AC5MZGF0YXN0cnVjdHVyZXMvYXJyYXloYXNodGFibGUvQXJyYXlIYXNoVGFibGU7eHB3CAAAAAFAAAAAc3IAFGphdmEubWF0aC5CaWdJbnRlZ2VyjPyfH6k7+x0DAAZJAAhiaXRDb3VudEkACWJpdExlbmd0aEkAE2ZpcnN0Tm9uemVyb0J5dGVOdW1JAAxsb3dlc3RTZXRCaXRJAAZzaWdudW1bAAltYWduaXR1ZGV0AAJbQnhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cP///////////////v////4AAAABdXIAAltCrPMX+AYIVOACAAB4cAAAABDORgSgAAAAAAAAAAAAAAABeHNxAH4AA3cIAAAAAEAAAAB4eA=="}
		AnnotationCmd:62 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"rule parameters setup"}}
		AssignHavocCmd CANON:61
		AssumeExpCmd LAnd(Ge(CANON:61 0x0(int) ) Le(CANON:61 0x2(int) ) )
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.PrimitiveArg","callIndex":0,"index":0,"sym":{"namePrefix":"CANON","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":61,"charByteOffset":22},"end":{"line":61,"charByteOffset":28}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]},"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"id":"x","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":61,"charByteOffset":22},"end":{"line":61,"charByteOffset":28}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"CANON","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":61,"charByteOffset":22},"end":{"line":61,"charByteOffset":28}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]}},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Parameter","sourceName":"x"},"fields":null}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":0}
		AnnotationCmd:63 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Setup"}}
		AnnotationCmd:64 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"multi contract setup"}}
		AssignExpCmd CANON1:58 R24:65
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":2}
		AnnotationCmd:66 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"contract address vars initialized"}}
		AssignExpCmd CANON2:59 R24:65
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":3}
		AnnotationCmd:67 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"setup read tracking instrumentation"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":4}
		AnnotationCmd:68 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"last storage initialize"}}
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":5}
		AnnotationCmd:69 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"}}
		AssignExpCmd R26:26 Select(tacExtcodesize!!3:9 Apply(to_skey:bif R24:65) )
		AssumeExpCmd Ge(R26:26 0x1 )
		AssumeExpCmd Eq(R26:70 R0:71 )
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":6}
		AnnotationCmd:72 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming address(0).code has no code deployed"}}
		AssignExpCmd B28:26 Eq(Select(tacExtcodesize!!3:9 Apply(skey_basic:bif 0x0) ) 0x0 )
		AssumeCmd B28:26
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":7}
		AnnotationCmd:73 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":8}
		AnnotationCmd:74 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about static addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":9}
		AnnotationCmd:75 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish addresses of precompiled contracts"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":10}
		AnnotationCmd:76 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about uniqueness of contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":11}
		AnnotationCmd:77 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"static links"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":12}
		AnnotationCmd:78 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"record starting nonces"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":13}
		AnnotationCmd:79 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"cloned contracts have no balances"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":14}
		AnnotationCmd:80 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Linked immutable setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":15}
		AnnotationCmd:81 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Constrain immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":16}
		AnnotationCmd:82 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish equivalence of extension and base contract immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":17}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1}
		AnnotationCmd:83 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.AssumeCmd.Assume","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":62,"charByteOffset":4},"end":{"line":62,"charByteOffset":18}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.ArithRelopExp.LtExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":62,"charByteOffset":12},"end":{"line":62,"charByteOffset":13}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"3","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"3"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":62,"charByteOffset":16},"end":{"line":62,"charByteOffset":17}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":62,"charByteOffset":12},"end":{"line":62,"charByteOffset":17}}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd:84 I29 CANON:61
		AssignExpCmd:85 I30 0x3
		AssignExpCmd:86 B31 true
		AnnotationCmd:87 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":18}
		AnnotationCmd:88 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"id":"s","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"s867"},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":[[{"#class":"tac.DataField.StructField","field":"b3"}],{"namePrefix":"CANON35","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b3"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"x"}],{"namePrefix":"CANON24","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.x"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"y"}],{"namePrefix":"CANON25","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.y"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"x"}],{"namePrefix":"CANON29","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.x"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"y"}],{"namePrefix":"CANON30","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.y"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"b1"}],{"namePrefix":"CANON36","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.b1"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"b2"}],{"namePrefix":"CANON37","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.b2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"b1"}],{"namePrefix":"CANON34","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.b1"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"b2"}],{"namePrefix":"CANON38","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.b2"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"x2"}],{"namePrefix":"CANON28","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.x2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"x2"}],{"namePrefix":"CANON33","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.x2"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"z1"}],{"namePrefix":"CANON26","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.z1"}]},[{"#class":"tac.DataField.StructField","field":"s1"},{"#class":"tac.DataField.StructField","field":"z2"}],{"namePrefix":"CANON27","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s1.z2"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"z1"}],{"namePrefix":"CANON31","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.z1"}]},[{"#class":"tac.DataField.StructField","field":"s2"},{"#class":"tac.DataField.StructField","field":"z2"}],{"namePrefix":"CANON32","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":63,"charByteOffset":4},"end":{"line":63,"charByteOffset":27}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.s2.z2"}]}]}}
		AnnotationCmd:89 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":19}
		AnnotationCmd:90 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Apply","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":4},"end":{"line":64,"charByteOffset":24}},"exp":{"#class":"spec.cvlast.CVLExp.ApplyExp.CVLFunction","id":"workOnSCVL","args":[{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":15},"end":{"line":64,"charByteOffset":16}}},"twoStateIndex":"NEITHER"},{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":18},"end":{"line":64,"charByteOffset":19}}},"twoStateIndex":"NEITHER"},"fieldName":"s2","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":18},"end":{"line":64,"charByteOffset":22}}}}],"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Void"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":4},"end":{"line":64,"charByteOffset":23}}},"noRevert":true},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
	}
	Block 1_0_0_1_0_1 Succ [2_0_0_0_0_0] {
		AnnotationCmd:91 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLFunctionStart","callIndex":1,"name":"workOnSCVL","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":64,"charByteOffset":4},"end":{"line":64,"charByteOffset":23}},"isNoRevert":true}}
		AssignExpCmd:92 I32 CANON:61
		AssignExpCmd:91 CANON40:49 I32
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.PrimitiveArg","callIndex":1,"index":0,"sym":{"namePrefix":"CANON40","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":20},"end":{"line":14,"charByteOffset":26}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"x"}]},"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"id":"x","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":20},"end":{"line":14,"charByteOffset":26}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp915916"},"src":{"id":"s867"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"s911912"},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"s2"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp915916"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"s908"},"src":{"id":"s911912"}}}
		AssignExpCmd CANON47:50 CANON29:39
		AssignExpCmd CANON48:51 CANON30:40
		AssignExpCmd CANON49:52 CANON31:41
		AssignExpCmd CANON50:53 CANON32:42
		AssignExpCmd CANON51:54 false
		AssignExpCmd CANON52:55 CANON33:43
		AssignExpCmd CANON53:56 CANON38:48
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLArg.StructArg","callIndex":1,"index":1,"symbols":[{"namePrefix":"CANON47","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.x"}]},{"namePrefix":"CANON48","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.y"}]},{"namePrefix":"CANON49","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.z1"}]},{"namePrefix":"CANON50","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.z2"}]},{"namePrefix":"CANON51","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b1"}]},{"namePrefix":"CANON52","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.x2"}]},{"namePrefix":"CANON53","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.struct.path","type":"spec.cvlast.CVLStructPathNode","erasureStrategy":"CallTrace"},"value":{"rootStructType":{"name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"fields":[{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Function","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"s.b2"}]}],"param":{"Named_type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"id":"s","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":14,"charByteOffset":28},"end":{"line":14,"charByteOffset":49}}}}}
		AnnotationCmd:93 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.AssumeCmd.Assume","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":4},"end":{"line":15,"charByteOffset":28}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.EqExp","l":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":13}}},"twoStateIndex":"NEITHER"},"fieldName":"b1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":16}}}},"r":{"#class":"spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp","l":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"x","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":22}}},"twoStateIndex":"NEITHER"},"r":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"3","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"3"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":25},"end":{"line":15,"charByteOffset":26}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":21},"end":{"line":15,"charByteOffset":26}},"hasParens":true}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":15,"charByteOffset":12},"end":{"line":15,"charByteOffset":27}}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.CVLFunctionScopeItem","scopeId":10}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp931932"},"src":{"id":"s908"}}}
		AssignExpCmd:94 B39 false
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"B39","tag":{"#class":"tac.Tag.Bool"},"callIndex":0}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"b1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp931932"}}}
		AssignExpCmd:95 I40 CANON40:49
		AssignExpCmd:96 I41 0x3
		AssignExpCmd:97 B42 false
		AssignExpCmd:98 CANON58:57 true
		AnnotationCmd:99 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":21}
		AnnotationCmd:91 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.CVLSnippetCmd.CVLFunctionEnd","callIndex":1,"name":"workOnSCVL"}}
		AnnotationCmd:91 JSON{"key":{"name":"revert.confluence","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		LabelCmd:91 "join point of revert handling"
	}
	Block 2_0_0_0_0_0 Succ [] {
		AnnotationCmd:91 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":20}
		AnnotationCmd:100 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Assert","range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":65,"charByteOffset":4},"end":{"line":65,"charByteOffset":19}},"exp":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"s","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Complex","fields":[{"fieldName":"s1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"s2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},{"fieldName":"b3","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":65,"charByteOffset":11},"end":{"line":65,"charByteOffset":12}}},"twoStateIndex":"NEITHER"},"fieldName":"s2","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"TestContract.Simple","fields":[{"fieldName":"x","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"y","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"}},{"fieldName":"z1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"z2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":8}},{"fieldName":"b1","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"fieldName":"x2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}},{"fieldName":"b2","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":65,"charByteOffset":11},"end":{"line":65,"charByteOffset":15}}}},"fieldName":"b1","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"test.spec","start":{"line":65,"charByteOffset":11},"end":{"line":65,"charByteOffset":18}}}},"description":"s.s2.b1","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":7}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp10261027"},"src":{"id":"s867"}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp10241025"},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"s2"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp10261027"}}}
		AssignExpCmd:101 B43 false
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"B43","tag":{"#class":"tac.Tag.Bool"},"callIndex":0}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"b1"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp10241025"}}}
		AssertCmd:102 false "s.s2.b1"
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
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacNonce",
        "maybeTACKeywordOrdinal": 44
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
  "2": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacContractsCreated",
        "maybeTACKeywordOrdinal": 46
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
  "3": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    }
  ],
  "4": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "4"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 256
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "4"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "5": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "2"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 256
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "2"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "6": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "0"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 256
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "0"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "7": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "7"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "7"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "8": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "1"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 160
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "1"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
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
  "10": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "3"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "3"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "11": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "8"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "8"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
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
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry",
        "name": "tacS",
        "maybeTACKeywordOrdinal": 2
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "13": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "5"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 160
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "5"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "14": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "5"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 168
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "5"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "15": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "5"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 176
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "5"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "16": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "1"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 160
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.address"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "1"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "17": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "6"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 256
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "6"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
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
        "name": "tacT",
        "maybeTACKeywordOrdinal": 5
      }
    },
    {
      "key": {
        "name": "tac.transient_storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    }
  ],
  "19": [
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
  "20": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 160
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
      }
    }
  ],
  "21": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 168
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
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
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 176
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
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
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    }
  ],
  "24": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 160
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.address"
      }
    }
  ],
  "25": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 256
      }
    }
  ],
  "26": [
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
  "27": [
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
  "28": [
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
  "29": [
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
  "30": [
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
  "31": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "1"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 168
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK",
        "bitwidth": 8
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "1"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "32": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "1"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 176
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "1"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    }
  ],
  "33": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
            "base": {
              "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
              "base": {
                "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
                "slot": "0"
              }
            },
            "offset": "5"
          }
        ]
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Packed",
        "packedStart": {
          "#class": "vc.data.ScalarizationSort.UnscalarizedBuffer"
        },
        "start": 0
      }
    },
    {
      "key": {
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 160
    },
    {
      "key": {
        "name": "tac.slot.type",
        "type": "spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "spec.cvlast.typedescriptors.EVMTypeDescriptor.address"
      }
    },
    {
      "key": {
        "name": "tac.storage.non-indexed-path",
        "type": "analysis.storage.StorageAnalysisResult$NonIndexedPath",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess",
        "base": {
          "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.MapAccess",
          "base": {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        },
        "offset": "5"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "35": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "36": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "37": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "38": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "39": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "40": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "41": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "43": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "44": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "45": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "46": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "47": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "48": [
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
            "line": 63,
            "charByteOffset": 4
          },
          "end": {
            "line": 63,
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
  "49": [
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
  "50": [
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
  "51": [
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
  "52": [
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
  "53": [
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
  "54": [
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
  "55": [
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
  "56": [
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
  "57": [
    {
      "key": {
        "name": "cvl",
        "type": "java.lang.Boolean",
        "erasureStrategy": "Canonical"
      },
      "value": true
    }
  ],
  "58": [
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
  "59": [
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
  "60": [
    {
      "key": {
        "name": "Tac.symbol.keyword",
        "type": "vc.data.TACSymbol$Var$KeywordEntry",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry",
        "name": "lastStorage"
      }
    }
  ],
  "61": [
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
            "line": 61,
            "charByteOffset": 22
          },
          "end": {
            "line": 61,
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
  "62": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    }
  ],
  "63": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1
    }
  ],
  "64": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 2
    }
  ],
  "65": [
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
  "66": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 3
    }
  ],
  "67": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 4
    }
  ],
  "68": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 5
    }
  ],
  "69": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 6
    }
  ],
  "70": [
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
  "71": [
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
  "72": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 7
    }
  ],
  "73": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    }
  ],
  "74": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 9
    }
  ],
  "75": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 10
    }
  ],
  "76": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 11
    }
  ],
  "77": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 12
    }
  ],
  "78": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 13
    }
  ],
  "79": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 14
    }
  ],
  "80": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 15
    }
  ],
  "81": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 16
    }
  ],
  "82": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 17
    }
  ],
  "83": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 18
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
          "line": 62,
          "charByteOffset": 4
        },
        "end": {
          "line": 62,
          "charByteOffset": 18
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
                  "scopeId": 7
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
                "line": 62,
                "charByteOffset": 12
              },
              "end": {
                "line": 62,
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
          "line": 62,
          "charByteOffset": 4
        },
        "end": {
          "line": 62,
          "charByteOffset": 18
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
                  "scopeId": 7
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
                "line": 62,
                "charByteOffset": 16
              },
              "end": {
                "line": 62,
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
          "line": 62,
          "charByteOffset": 4
        },
        "end": {
          "line": 62,
          "charByteOffset": 18
        }
      }
    }
  ],
  "86": [
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
                    "scopeId": 7
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
                  "line": 62,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 62,
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
                    "scopeId": 7
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
                  "line": 62,
                  "charByteOffset": 16
                },
                "end": {
                  "line": 62,
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
                  "scopeId": 7
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
                "line": 62,
                "charByteOffset": 12
              },
              "end": {
                "line": 62,
                "charByteOffset": 17
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "I29",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0
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
                    "scopeId": 7
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
                  "line": 62,
                  "charByteOffset": 12
                },
                "end": {
                  "line": 62,
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
                    "scopeId": 7
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
                  "line": 62,
                  "charByteOffset": 16
                },
                "end": {
                  "line": 62,
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
          "line": 62,
          "charByteOffset": 4
        },
        "end": {
          "line": 62,
          "charByteOffset": 18
        }
      }
    }
  ],
  "87": [
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
          "line": 62,
          "charByteOffset": 4
        },
        "end": {
          "line": 62,
          "charByteOffset": 18
        }
      }
    }
  ],
  "88": [
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
          "line": 63,
          "charByteOffset": 4
        },
        "end": {
          "line": 63,
          "charByteOffset": 27
        }
      }
    }
  ],
  "89": [
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
          "line": 63,
          "charByteOffset": 4
        },
        "end": {
          "line": 63,
          "charByteOffset": 27
        }
      }
    }
  ],
  "90": [
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
          "line": 64,
          "charByteOffset": 4
        },
        "end": {
          "line": 64,
          "charByteOffset": 24
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
        "specFile": "test.spec",
        "start": {
          "line": 64,
          "charByteOffset": 4
        },
        "end": {
          "line": 64,
          "charByteOffset": 24
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
                  "scopeId": 7
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
                "line": 64,
                "charByteOffset": 15
              },
              "end": {
                "line": 64,
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
          "line": 64,
          "charByteOffset": 4
        },
        "end": {
          "line": 64,
          "charByteOffset": 24
        }
      }
    }
  ],
  "93": [
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
  "95": [
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
  "96": [
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
  "97": [
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
            "namePrefix": "I40",
            "tag": {
              "#class": "tac.Tag.Int"
            },
            "callIndex": 0
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
  "98": [
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
            "namePrefix": "CANON59",
            "tag": {
              "#class": "tac.Tag.Bool"
            },
            "callIndex": 0
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
  "99": [
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
  "100": [
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
          "line": 65,
          "charByteOffset": 4
        },
        "end": {
          "line": 65,
          "charByteOffset": 19
        }
      }
    }
  ],
  "101": [
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
                      "scopeId": 7
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
                    "line": 65,
                    "charByteOffset": 11
                  },
                  "end": {
                    "line": 65,
                    "charByteOffset": 12
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "s2",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 7
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
                  "line": 65,
                  "charByteOffset": 11
                },
                "end": {
                  "line": 65,
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
                  "scopeId": 7
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
                "line": 65,
                "charByteOffset": 11
              },
              "end": {
                "line": 65,
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
          "line": 65,
          "charByteOffset": 4
        },
        "end": {
          "line": 65,
          "charByteOffset": 19
        }
      }
    }
  ],
  "102": [
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
          "line": 65,
          "charByteOffset": 4
        },
        "end": {
          "line": 65,
          "charByteOffset": 19
        }
      }
    }
  ]
}