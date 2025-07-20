TACSymbolTable {
	UserDefined {
		UninterpSort skey
	}
	BuiltinFunctions {
		to_skey:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.ToSkey"}
		skey_basic:JSON{"#class":"vc.data.TACBuiltInFunction.Hash.Basic"}
		safe_math_narrow_bv256:JSON{"#class":"vc.data.TACBuiltInFunction.SafeMathNarrow","returnSort":{"bits":256}}
	}
	UninterpretedFunctions {
	}
	tacM0x40@1:bv256:0
	tacCodesizeCANON0:bv256:1
	tacCalldatabufCANON0@1:bv256:2
	tacCalldatabufCANON1@1:bool:3
	tacM0x40!!0:bv256:0
	tacAddress!!17:bv256:4
	lastReverted:bool:5
	CANON11!!3:bv256:6
	tacExtcodesize!!2:wordmap:7
	CANON6!!14:int:8
	CANON6!!29:int:8
	tacCalldatasize@1:bv256:9
	tacExtcodesize:wordmap:7
	CANON8!7:ghostmap(bv256*bv256*bv256->bv256):10
	tacSighash!!9:bv256:11
	tacCalldatasize!!15:bv256:9
	tacCalldatabuf@1:bytemap:12
	R1:bv256:1
	R4:bv256:13
	R5:bv256:14
	R6:bv256:15
	R8:bv256:16
	B11:bool:17
	B12:bool:17
	B13:bool:18
	B21:bool:19
	B22:bool:18
	I19:int:20
	I20:int:21
	R10:bv256:17
	R16:bv256:17
	R18:bv256:17
	R23:bv256:22
	R24:bv256:23
	R27:bv256:24
	R28:bv256:25
	tacAddress@1:bv256:4
	CANON11!!25:bv256:6
	CANON11!!26:bv256:6
	LCANON0@1:bv256:24
	LCANON1@1:bv256:25
	LCANON2@1:bv256:22
	LCANON3@1:bv256:26
	CANON10@1:bv256:17
	CANON11:bv256:6
	CANON12:int:27
	CANON13:int:21
	CANON14:bool:19
	CANON15:bool:18
	tacContractAtCANON1:bv256:13
	tacContractAtCANON2:bv256:14
	tacContractAtCANON3:bv256:15
	CANON1:bv256:17
	CANON2:bool:17
	CANON3:bool:17
	CANON4:bool:28
	CANON5:bool:18
	CANON6:int:8
	CANON8:ghostmap(bv256*bv256*bv256->bv256):10
	CANON9:bv256:17
	tacContractAtCANON:bv256:16
	tacSighash@1:bv256:11
	CANON:int:29
}
Program {
	Block 0_0_0_0_0_0 Succ [1_0_0_1_0_0] {
		AssignHavocCmd tacM0x40!!0:0
		AssumeExpCmd Le(tacM0x40!!0:0 0x80 )
		AssignHavocCmd R1:1
		AssumeExpCmd Ge(R1:1 0x1 )
		AssignHavocCmd tacExtcodesize!!2:7
		AssignHavocCmd CANON11!!3:6
		AssignHavocCmd R4:13
		AssumeExpCmd Eq(R4:13 0x1 )
		AssignHavocCmd R5:14
		AssumeExpCmd Eq(R5:14 0x2 )
		AssignHavocCmd R6:15
		AssumeExpCmd Eq(R6:15 0x4 )
		AssignHavocCmd CANON4:28
		AssignHavocCmd CANON8!7:10
		AssignHavocCmd R8:16
		AssumeExpCmd LAnd(Ge(R8:16 0x1 ) Le(R8:16 0xffffffffffffffffffffffffffffffffffffffff ) )
		AssignHavocCmd tacSighash!!9:11
		AssumeExpCmd Eq(tacSighash!!9:11 0x3fe67f11 )
		AnnotationCmd JSON{"key":{"name":"tac.state.extension","type":"analysis.icfg.Inliner$ExtendedStateVars","erasureStrategy":"Canonical"},"value":"rO0ABXNyACdhbmFseXNpcy5pY2ZnLklubGluZXIkRXh0ZW5kZWRTdGF0ZVZhcnOvh/MjxNFkQAIAAUwAFmluc3RhbmNlVG9FeHRlbmRlZFZhcnN0AA9MamF2YS91dGlsL01hcDt4cHNyACFkYXRhc3RydWN0dXJlcy5MaW5rZWRBcnJheUhhc2hNYXAAAAAAAAAAAQMAAkYACmxvYWRGYWN0b3JMAAloYXNoVGFibGV0AC5MZGF0YXN0cnVjdHVyZXMvYXJyYXloYXNodGFibGUvQXJyYXlIYXNoVGFibGU7eHB3CAAAAAFAAAAAc3IAFGphdmEubWF0aC5CaWdJbnRlZ2VyjPyfH6k7+x0DAAZJAAhiaXRDb3VudEkACWJpdExlbmd0aEkAE2ZpcnN0Tm9uemVyb0J5dGVOdW1JAAxsb3dlc3RTZXRCaXRJAAZzaWdudW1bAAltYWduaXR1ZGV0AAJbQnhyABBqYXZhLmxhbmcuTnVtYmVyhqyVHQuU4IsCAAB4cP///////////////v////4AAAABdXIAAltCrPMX+AYIVOACAAB4cAAAABDORgSgAAAAAAAAAAAAAAABeHNxAH4AA3cIAAAAAEAAAAB4eA=="}
		AnnotationCmd:30 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"rule parameters setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":0}
		AnnotationCmd:31 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Setup"}}
		AnnotationCmd:32 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"multi contract setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":2}
		AnnotationCmd:33 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"contract address vars initialized"}}
		AssignExpCmd CANON:29 R8:34
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":3}
		AnnotationCmd:35 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"setup read tracking instrumentation"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":4}
		AnnotationCmd:36 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"last storage initialize"}}
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":5}
		AnnotationCmd:37 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming contracts in scene with non-empty bytecode have EXTCODESIZE larger than zero"}}
		AssignExpCmd R10:17 Select(tacExtcodesize!!2:7 Apply(to_skey:bif R8:34) )
		AssumeExpCmd Ge(R10:17 0x1 )
		AssumeExpCmd Eq(R10:38 R1:39 )
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":6}
		AnnotationCmd:40 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assuming address(0).code has no code deployed"}}
		AssignExpCmd B12:17 Eq(Select(tacExtcodesize!!2:7 Apply(skey_basic:bif 0x0) ) 0x0 )
		AssumeCmd B12:17 "expToAssumeCmd"
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":7}
		AnnotationCmd:41 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":8}
		AnnotationCmd:42 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about static addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":9}
		AnnotationCmd:43 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish addresses of precompiled contracts"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":10}
		AnnotationCmd:44 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"assumptions about uniqueness of contracts' addresses"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":11}
		AnnotationCmd:45 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"static links"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":12}
		AnnotationCmd:46 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"record starting nonces"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":13}
		AnnotationCmd:47 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"cloned contracts have no balances"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":14}
		AnnotationCmd:48 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Linked immutable setup"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":15}
		AnnotationCmd:49 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Constrain immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":16}
		AnnotationCmd:50 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"establish equivalence of extension and base contract immutables"}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":17}
		AnnotationCmd:51 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Message","s":"Assuming all requireInvariants commands globally"}}
		AnnotationCmd JSON{"key":{"name":"cvl.assume.invariant.target","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":18}
		AnnotationCmd JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1}
		AnnotationCmd:52 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Declaration","range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":6,"charByteOffset":4},"end":{"line":6,"charByteOffset":11}},"cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"id":"b","scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.declaration","type":"spec.CVLCompiler$Companion$TraceMeta$VariableDeclaration","erasureStrategy":"Erased"},"value":{"v":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"CANON4","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule","range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":6,"charByteOffset":4},"end":{"line":6,"charByteOffset":11}}}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"b"}]}},"t":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"type":{"#class":"spec.CVLCompiler.Companion.TraceMeta.DeclarationType.Variable"},"fields":null}}
		AnnotationCmd:53 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":19}
		AnnotationCmd:54 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Definition","range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":7,"charByteOffset":4},"end":{"line":7,"charByteOffset":45}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"Basic.MyStruct","fields":[{"fieldName":"num","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]},"idL":[{"#class":"spec.cvlast.CVLLhs.Id","range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":7,"charByteOffset":4},"end":{"line":7,"charByteOffset":27}},"id":"myStruct","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"Basic.MyStruct","fields":[{"fieldName":"num","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":7,"charByteOffset":4},"end":{"line":7,"charByteOffset":27}}}}],"exp":{"#class":"spec.cvlast.CVLExp.ApplyExp.ContractFunction.Concrete","methodIdWithCallContext":{"#class":"spec.cvlast.ConcreteMethod","signature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"Basic"},"methodId":"getMyStruct"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"b","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$bool"},"range":{"#class":"utils.Range.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMStructDescriptor","canonicalId":"Basic.sol|Basic.MyStruct","location":"memory","fields":[{"fieldName":"num","fieldType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}}],"name":"Basic.MyStruct"},"range":{"#class":"utils.Range.Empty"}}]},"sighashInt":{"n":"3fe67f11"}}},"args":[{"#class":"spec.cvlast.CVLExp.VariableExp","id":"b","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":7,"charByteOffset":42},"end":{"line":7,"charByteOffset":43}}},"twoStateIndex":"NEITHER"}],"noRevert":true,"storage":{"id":"lastStorage","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"},"range":{"#class":"utils.Range.Empty","comment":"empty storage type"}},"twoStateIndex":"NEITHER"},"isWhole":false,"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.VM","descriptor":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMStructDescriptor","canonicalId":"Basic.sol|Basic.MyStruct","location":"memory","fields":[{"fieldName":"num","fieldType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}}],"name":"Basic.MyStruct"},"context":{"#class":"spec.cvlast.typedescriptors.FromVMContext.ReturnValue"}},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":7,"charByteOffset":30},"end":{"line":7,"charByteOffset":44}},"annotation":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.CallResolution$DirectPassing","target":{"methodSignature":{"#class":"spec.cvlast.ExternalQualifiedMethodSignature.ExternalQualifiedMethodSig","wrapped":{"#class":"spec.cvlast.QualifiedMethodSignature.QualifiedMethodSig","qualifiedMethodName":{"#class":"spec.cvlast.QualifiedFunction","host":{"name":"Basic"},"methodId":"getMyStruct"},"params":[{"#class":"spec.cvlast.VMParam.Named","name":"b","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$bool"},"range":{"#class":"utils.Range.Empty"}}],"res":[{"#class":"spec.cvlast.VMParam.Unnamed","vmType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMStructDescriptor","canonicalId":"Basic.sol|Basic.MyStruct","location":"memory","fields":[{"fieldName":"num","fieldType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}}],"name":"Basic.MyStruct"},"range":{"#class":"utils.Range.Empty"}}]},"sighashInt":{"n":"3fe67f11"}},"definitelyNonPayable":true,"annotation":{"visibility":"EXTERNAL","envFree":true,"library":false,"virtual":false},"stateMutability":"nonpayable","evmExternalMethodInfo":{"sigHash":"3fe67f11","name":"getMyStruct","argTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.bool"}],"resultTypes":[{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.EVMStructDescriptor","canonicalId":"Basic.sol|Basic.MyStruct","location":"memory","fields":[{"fieldName":"num","fieldType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}}],"name":"Basic.MyStruct"}],"stateMutability":"nonpayable","isConstant":false,"paramNames":["b"],"isLibrary":false,"contractName":"Basic","contractInstanceId":"ce4604a0000000000000000000000001","sourceSegment":{"range":{"specFile":"Basic.sol","start":{"line":10,"charByteOffset":4},"end":{"line":15,"charByteOffset":5}},"content":"function getMyStruct(bool b) external returns (MyStruct memory) {\n        if (b) {\n            ms.num = ms.num + 1;\n        }\n        return ms;\n    }"}}},"hasEnv":false}}},"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AssignExpCmd:55 B13:18 CANON4:28
	}
	Block 1_0_0_1_0_0 Succ [1_0_0_1_0_7, 111_1021_0_1_0_0] {
		AssignHavocCmd CANON6!!14:8
		AnnotationCmd:56 JSON{"key":{"name":"call.trace.push","type":"analysis.icfg.Inliner$CallStack$PushRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"3fe67f11"},"attr":{"#class":"scene.MethodAttribute.Common"}},"summary":null,"convention":{"#class":"analysis.icfg.Inliner.CallConventionType.FromCVL"},"calleeId":1}}
		AssignHavocCmd:56 tacCalldatasize!!15:9
		AssumeExpCmd Eq(tacCalldatasize!!15:9 0x24 )
		AssignExpCmd:56 tacCalldatabuf@1:12 MapDefinition(CANON7.231:bv256 -> Ite(Lt(CANON7.231 tacCalldatasize!!15:9 ) Select(Select(Select(CANON8!7:10 CANON7.231 ) tacCalldatasize!!15:9 ) 0x3fe67f11 ) 0x0 ) bytemap)
		AssignExpCmd:56 R16:17 Select(Select(Select(CANON8!7:10 0x0 ) 0x24 ) 0x3fe67f11 )
		AssumeExpCmd LAnd(Ge(R16:17 0x3fe67f1100000000000000000000000000000000000000000000000000000000 ) Le(R16:17 0x3fe67f11ffffffffffffffffffffffffffffffffffffffffffffffffffffffff ) )
		AnnotationCmd:56 JSON{"key":{"name":"cvl.arg-serialization.start","type":"spec.CVLInvocationCompiler$StartSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		AssignExpCmd:56 tacCalldatabufCANON0@1:2 Select(tacCalldatabuf@1:57 0x0 )
		LabelCmd:56 "3: Read primitive from certoraArg102103:bool..."
		AssignExpCmd:56 tacCalldatabufCANON1@1:58 B13:18
		LabelCmd:56 "...done 3"
		AnnotationCmd JSON{"key":{"name":"cvl.trace.external","type":"spec.CVLCompiler$Companion$TraceMeta$ExternalArg","erasureStrategy":"Erased"},"value":{"s":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"B13","tag":{"#class":"tac.Tag.Bool"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"}}]}},"rootOffset":"0","callId":1,"targetType":{"#class":"ReflectivePolymorphicSerializer::spec.cvlast.typedescriptors.EVMTypeDescriptor$bool"},"sourceType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"fields":null}}
		AnnotationCmd:56 JSON{"key":{"name":"cvl.arg-serialization.end","type":"spec.CVLInvocationCompiler$EndSerializationMarker","erasureStrategy":"Canonical"},"value":{"id":0,"callId":1}}
		LabelCmd:56 "Start procedure Basic-getMyStruct(bool)"
		AnnotationCmd:56 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AssignExpCmd:56 R18:17 Select(tacExtcodesize!!2:7 Apply(to_skey:bif R8:59) )
		AssumeExpCmd Ge(R18:17 0x1 )
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym","isLoad":true,"loc":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R8","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"tacAddress","maybeTACKeywordOrdinal":22}},{"key":{"name":"tac.env.known-bit-width","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":160},{"key":{"name":"tac.contract.sym.addr.name","type":"java.lang.String","erasureStrategy":"Erased"},"value":"Basic"},{"key":{"name":"tac.contract.sym.addr","type":"java.math.BigInteger","erasureStrategy":"Erased"},"value":"ce4604a0000000000000000000000001"}]},"contractInstance":"ce4604a0000000000000000000000001","value":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R18","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.is-temp-var","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}]},"storageType":null,"range":null}}
		AnnotationCmd:56 JSON{"key":{"name":"internal.func.finder.info","type":"analysis.ip.InternalFunctionFinderReport","erasureStrategy":"Erased"},"value":{"unresolvedFunctions":[]}}
		AnnotationCmd:56 JSON{"key":{"name":"fps.free-pointer-is-scalarized","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":0,"bytecodeCount":8,"sources":[{"source":0,"begin":25,"end":267}]}}
		LabelCmd "→ Assuming FP is strictly monotonic increasing"
		LabelCmd "←"
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":0,"branchSource":{"range":{"specFile":"Basic.sol","start":{"line":2,"charByteOffset":0},"end":{"line":16,"charByteOffset":1}},"content":"contract Basic {...}"}}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":0}}
		AnnotationCmd:60 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":16,"bytecodeCount":7,"sources":[{"source":0,"begin":25,"end":267}]}}
		AnnotationCmd:61 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":26,"bytecodeCount":9,"sources":[{"source":0,"begin":25,"end":267}]}}
		AnnotationCmd:62 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":48,"bytecodeCount":14,"sources":[{"source":0,"begin":115,"end":265}]}}
		AnnotationCmd:63 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":206,"bytecodeCount":10,"sources":[{"source":1,"begin":146,"end":402},{"source":1,"begin":251,"end":253},{"source":1,"begin":239,"end":248},{"source":1,"begin":230,"end":237},{"source":1,"begin":226,"end":249},{"source":1,"begin":222,"end":254},{"source":1,"begin":219,"end":221}]}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":1,"branchSource":{"range":{"specFile":"Basic.sol","start":{"line":10,"charByteOffset":4},"end":{"line":15,"charByteOffset":5}},"content":"compiler-generate condition in function getMyStruct(bool b) external returns (MyStruct memory) "}}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":1}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":224,"bytecodeCount":9,"sources":[{"source":1,"begin":219,"end":221},{"source":1,"begin":310,"end":311},{"source":1,"begin":335,"end":385},{"source":1,"begin":377,"end":384},{"source":1,"begin":368,"end":374},{"source":1,"begin":357,"end":366},{"source":1,"begin":353,"end":375}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":185,"bytecodeCount":10,"sources":[{"source":1,"begin":7,"end":140},{"source":1,"begin":88,"end":94},{"source":1,"begin":75,"end":95},{"source":1,"begin":66,"end":95},{"source":1,"begin":104,"end":134},{"source":1,"begin":128,"end":133}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":472,"bytecodeCount":5,"sources":[{"source":1,"begin":1906,"end":2022},{"source":1,"begin":1976,"end":1997},{"source":1,"begin":1991,"end":1996}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":403,"bytecodeCount":11,"sources":[{"source":1,"begin":1541,"end":1631},{"source":1,"begin":1618,"end":1623},{"source":1,"begin":1611,"end":1624},{"source":1,"begin":1604,"end":1625},{"source":1,"begin":1593,"end":1625},{"source":1,"begin":1583,"end":1631}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":481,"bytecodeCount":5,"sources":[{"source":1,"begin":1976,"end":1997},{"source":1,"begin":1969,"end":1974},{"source":1,"begin":1966,"end":1998},{"source":1,"begin":1956,"end":1958}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":492,"bytecodeCount":3,"sources":[{"source":1,"begin":1956,"end":1958},{"source":1,"begin":1946,"end":2022}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":200,"bytecodeCount":6,"sources":[{"source":1,"begin":104,"end":134},{"source":1,"begin":56,"end":140}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":238,"bytecodeCount":9,"sources":[{"source":1,"begin":335,"end":385},{"source":1,"begin":325,"end":385},{"source":1,"begin":281,"end":395},{"source":1,"begin":209,"end":402}]}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":69,"bytecodeCount":3,"sources":[{"source":0,"begin":115,"end":265}]}}
		AnnotationCmd:64 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":96,"bytecodeCount":4,"sources":[{"source":0,"begin":115,"end":265},{"source":0,"begin":162,"end":177}]}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":166,"bytecodeCount":15,"sources":[{"source":-1,"begin":-1,"end":-2}]}}
		LabelCmd "→ Assuming FP is strictly monotonic increasing"
		LabelCmd "←"
		AnnotationCmd:56 JSON{"key":{"name":"pta.end-allocation","type":"analysis.alloc.AllocationAnalysis$AbstractLocation","erasureStrategy":"Erased"},"value":{"prevFPWriteIdx":0,"nextFPWriteCmd":{"block":{"#class":"tac.BlockIdentifier","origStartPc":492,"stkTop":1012,"decompCopy":0,"calleeIdx":0,"topOfStackValue":0,"freshCopy":0},"pos":14},"sort":{"#class":"analysis.alloc.AllocationAnalysis.Alloc.ConstBlock","sz":"20"}}}
		AnnotationCmd:65 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":104,"bytecodeCount":5,"sources":[{"source":0,"begin":162,"end":177},{"source":0,"begin":193,"end":194},{"source":0,"begin":189,"end":240}]}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":2,"branchSource":{"range":{"specFile":"Basic.sol","start":{"line":11,"charByteOffset":8},"end":{"line":13,"charByteOffset":9}},"content":"if (b) "}}}
		JumpiCmd:66 111_1021_0_1_0_0 1_0_0_1_0_7 B13:18
	}
	Block 2_0_0_0_0_0 Succ [] {
		AnnotationCmd JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot","lhs":{"namePrefix":"lastStorage","tag":{"#class":"tac.Tag.BlockchainState"},"callIndex":0,"meta":[{"key":{"name":"cvl.def.site","type":"spec.CVLDefinitionSite","erasureStrategy":"Canonical"},"value":{"#class":"spec.CVLDefinitionSite.Rule"}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.NonTACKeywordEntry","name":"lastStorage"}},{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.VMInternal.BlockchainState"}},{"key":{"name":"cvl","type":"java.lang.Boolean","erasureStrategy":"Canonical"},"value":true},{"key":{"name":"cvl.display","type":"java.lang.String","erasureStrategy":"CallTrace"},"value":"lastStorage"}]}}}
		AnnotationCmd:56 JSON{"key":{"name":"cvl.label.end","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":20}
		AnnotationCmd:67 JSON{"key":{"name":"cvl.label.start","type":"report.calltrace.CVLReportLabel","erasureStrategy":"CallTrace"},"value":{"#class":"report.calltrace.CVLReportLabel.Cmd","cmd":{"cmd_type":"spec.cvlast.CVLCmd.Simple.Assert","range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":8,"charByteOffset":4},"end":{"line":8,"charByteOffset":28}},"exp":{"#class":"spec.cvlast.CVLExp.RelopExp.ArithRelopExp.GtExp","l":{"#class":"spec.cvlast.CVLExp.FieldSelectExp","structExp":{"#class":"spec.cvlast.CVLExp.VariableExp","id":"myStruct","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Struct","name":"Basic.MyStruct","fields":[{"fieldName":"num","cvlType":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":8,"charByteOffset":11},"end":{"line":8,"charByteOffset":19}}},"twoStateIndex":"NEITHER"},"fieldName":"num","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":8,"charByteOffset":11},"end":{"line":8,"charByteOffset":23}}}},"r":{"#class":"spec.cvlast.CVLExp.Constant.NumberLit","n":"0","tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral","n":"0"},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":8,"charByteOffset":26},"end":{"line":8,"charByteOffset":27}}}},"tag":{"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}},"type":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.Bool"},"range":{"#class":"utils.Range.Range","specFile":"Basic.spec","start":{"line":8,"charByteOffset":11},"end":{"line":8,"charByteOffset":27}}}},"description":null,"scope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"},{"#class":"spec.cvlast.CVLScope.Item.RuleScopeItem","scopeId":0}],"innerScope":{"scopeStack":[{"#class":"spec.cvlast.CVLScope.Item.AstScopeItem"}],"innerScope":{"scopeStack":[],"innerScope":null}}}}}}
		AnnotationCmd JSON{"key":{"name":"cvl.trace.data.movement","type":"spec.CVLCompiler$Companion$TraceMeta$CVLMovement","erasureStrategy":"Erased"},"value":{"dst":{"id":"tmp140141"},"src":{"id":"myStruct101"}}}
		AssignExpCmd:68 I20:21 CANON6!!29:69
		AnnotationCmd JSON{"key":{"name":"cvl.trace.traversal","type":"spec.CVLCompiler$Companion$TraceMeta$ValueTraversal","erasureStrategy":"Erased"},"value":{"lhs":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar","t":{"namePrefix":"I20","tag":{"#class":"tac.Tag.Int"},"callIndex":0,"meta":[{"key":{"name":"cvl.type","type":"spec.cvlast.CVLType$PureCVLType","erasureStrategy":"CallTrace"},"value":{"#class":"spec.cvlast.CVLType.PureCVLType.Primitive.UIntK","k":256}}]}},"ap":[{"#class":"ReflectivePolymorphicSerializer::spec.CVLCompiler$Companion$TraceMeta$CVLAccessPathStep$Field","f":"num"}],"base":{"#class":"spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar","id":"tmp140141"}}}
		AssignExpCmd:70 B21:19 false
		AssignExpCmd:71 B22:18 Gt(CANON6!!29:69 0x0(int) )
		AssertCmd:72 B22:18 "myStruct.num > 0"
	}
	Block 111_1021_0_1_0_0 Succ [136_1021_0_1_0_0] {
		AnnotationCmd:66 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":111,"bytecodeCount":10,"sources":[{"source":0,"begin":228,"end":229},{"source":0,"begin":219,"end":221},{"source":0,"begin":219,"end":225},{"source":0,"begin":219,"end":229}]}}
		AssumeExpCmd Le(CANON11!!3:6 0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe )
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet","value":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"CANON11!!3","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.storage.non-indexed-path.family","type":"analysis.storage.StorageAnalysisResult$StoragePaths","erasureStrategy":"Canonical"},"value":{"storagePaths":[{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}]}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.scalarization.sort","type":"vc.data.ScalarizationSort","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.ScalarizationSort.Split","idx":"0"}},{"key":{"name":"tac.storage.bit-width","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":256},{"key":{"name":"tac.storage.pretty.paths","type":"analysis.storage.DisplayPaths","erasureStrategy":"Erased"},"value":{"paths":[{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}}]}},{"key":{"name":"tac.slot.type","type":"spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType","erasureStrategy":"Canonical"},"value":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}},{"key":{"name":"tac.storage.non-indexed-path","type":"analysis.storage.StorageAnalysisResult$NonIndexedPath","erasureStrategy":"Canonical"},"value":{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}},{"key":{"name":"tac.storage","type":"java.math.BigInteger","erasureStrategy":"Canonical"},"value":"ce4604a0000000000000000000000001"},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1019}]},"displayPath":{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}},"storageType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"contractInstance":"ce4604a0000000000000000000000001","range":{"specFile":"Basic.sol","start":{"line":12,"charByteOffset":21},"end":{"line":12,"charByteOffset":27}},"linkableStorageReadId":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet.None"}}}
		AnnotationCmd:73 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":317,"bytecodeCount":6,"sources":[{"source":1,"begin":1230,"end":1535},{"source":1,"begin":1289,"end":1309},{"source":1,"begin":1307,"end":1308}]}}
		AnnotationCmd:73 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":415,"bytecodeCount":9,"sources":[{"source":1,"begin":1637,"end":1714},{"source":1,"begin":1703,"end":1708},{"source":1,"begin":1692,"end":1708},{"source":1,"begin":1682,"end":1714}]}}
		AnnotationCmd:73 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":328,"bytecodeCount":7,"sources":[{"source":1,"begin":1289,"end":1309},{"source":1,"begin":1284,"end":1309},{"source":1,"begin":1323,"end":1343},{"source":1,"begin":1341,"end":1342}]}}
		AnnotationCmd:73 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":415,"bytecodeCount":9,"sources":[{"source":1,"begin":1637,"end":1714},{"source":1,"begin":1703,"end":1708},{"source":1,"begin":1692,"end":1708},{"source":1,"begin":1682,"end":1714}]}}
		AnnotationCmd:73 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":339,"bytecodeCount":11,"sources":[{"source":1,"begin":1323,"end":1343},{"source":1,"begin":1318,"end":1343},{"source":1,"begin":1477,"end":1478},{"source":1,"begin":1409,"end":1475},{"source":1,"begin":1405,"end":1479},{"source":1,"begin":1402,"end":1403},{"source":1,"begin":1399,"end":1480},{"source":1,"begin":1396,"end":1398}]}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.StartBranchSnippet","branchIndex":3,"branchSource":{"range":{"specFile":"Basic.sol","start":{"line":12,"charByteOffset":21},"end":{"line":12,"charByteOffset":31}},"content":"ms.num + 1"}}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":3}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":392,"bytecodeCount":11,"sources":[{"source":1,"begin":1396,"end":1398},{"source":1,"begin":1527,"end":1528},{"source":1,"begin":1524,"end":1525},{"source":1,"begin":1520,"end":1529},{"source":1,"begin":1513,"end":1529},{"source":1,"begin":1274,"end":1535}]}}
		AssignExpCmd:74 R24:23 Apply(safe_math_narrow_bv256:bif IntAdd(0x1 CANON11!!3:75))
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":127,"bytecodeCount":8,"sources":[{"source":0,"begin":219,"end":229},{"source":0,"begin":210,"end":212},{"source":0,"begin":210,"end":216},{"source":0,"begin":210,"end":229}]}}
		AssignExpCmd:76 CANON11!!25:6 Apply(safe_math_narrow_bv256:bif IntAdd(0x1 CANON11!!3:75))
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet.StoreSnippet","value":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"R24","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1016}]},"displayPath":{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}},"storageType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"contractInstance":"ce4604a0000000000000000000000001","range":{"specFile":"Basic.sol","start":{"line":12,"charByteOffset":12},"end":{"line":12,"charByteOffset":31}}}}
		LabelCmd "Parallel assignment for CANON11!!26:bv256 := CANON11!!25:bv256"
		AssignExpCmd CANON11!!26:6 Apply(safe_math_narrow_bv256:bif IntAdd(0x1 CANON11!!3:75))
	}
	Block 136_1021_0_1_0_0 Succ [2_0_0_0_0_0] {
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.BranchSnippet.EndBranchSnippet","branchIndex":2}}
		AnnotationCmd:56 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":136,"bytecodeCount":25,"sources":[{"source":0,"begin":189,"end":240},{"source":0,"begin":256,"end":258},{"source":0,"begin":249,"end":258},{"source":0,"begin":115,"end":265}]}}
		LabelCmd "→ Assuming FP is strictly monotonic increasing"
		LabelCmd "←"
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet","value":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"CANON11!!26","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.storage.non-indexed-path.family","type":"analysis.storage.StorageAnalysisResult$StoragePaths","erasureStrategy":"Canonical"},"value":{"storagePaths":[{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}]}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.scalarization.sort","type":"vc.data.ScalarizationSort","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.ScalarizationSort.Split","idx":"0"}},{"key":{"name":"tac.storage.bit-width","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":256},{"key":{"name":"tac.storage.pretty.paths","type":"analysis.storage.DisplayPaths","erasureStrategy":"Erased"},"value":{"paths":[{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}}]}},{"key":{"name":"tac.slot.type","type":"spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType","erasureStrategy":"Canonical"},"value":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}},{"key":{"name":"tac.storage.non-indexed-path","type":"analysis.storage.StorageAnalysisResult$NonIndexedPath","erasureStrategy":"Canonical"},"value":{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}},{"key":{"name":"tac.storage","type":"java.math.BigInteger","erasureStrategy":"Canonical"},"value":"ce4604a0000000000000000000000001"},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1017}]},"displayPath":{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}},"storageType":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256},"contractInstance":"ce4604a0000000000000000000000001","range":{"specFile":"Basic.sol","start":{"line":14,"charByteOffset":8},"end":{"line":14,"charByteOffset":17}},"linkableStorageReadId":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet.LoadSnippet.Id","id":0}}}
		AnnotationCmd:56 JSON{"key":{"name":"pta.end-allocation","type":"analysis.alloc.AllocationAnalysis$AbstractLocation","erasureStrategy":"Erased"},"value":{"prevFPWriteIdx":1,"nextFPWriteCmd":{"block":{"#class":"tac.BlockIdentifier","origStartPc":136,"stkTop":1021,"decompCopy":0,"calleeIdx":0,"topOfStackValue":0,"freshCopy":0},"pos":4},"sort":{"#class":"analysis.alloc.AllocationAnalysis.Alloc.ConstBlock","sz":"20"}}}
		AnnotationCmd:77 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":74,"bytecodeCount":8,"sources":[{"source":0,"begin":115,"end":265}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":290,"bytecodeCount":14,"sources":[{"source":1,"begin":910,"end":1224},{"source":1,"begin":1087,"end":1089},{"source":1,"begin":1076,"end":1085},{"source":1,"begin":1072,"end":1090},{"source":1,"begin":1064,"end":1090},{"source":1,"begin":1100,"end":1217},{"source":1,"begin":1214,"end":1215},{"source":1,"begin":1203,"end":1212},{"source":1,"begin":1199,"end":1216},{"source":1,"begin":1190,"end":1196}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":247,"bytecodeCount":15,"sources":[{"source":1,"begin":462,"end":790},{"source":1,"begin":605,"end":609},{"source":1,"begin":600,"end":603},{"source":1,"begin":596,"end":610},{"source":1,"begin":691,"end":695},{"source":1,"begin":684,"end":689},{"source":1,"begin":680,"end":696},{"source":1,"begin":674,"end":697},{"source":1,"begin":710,"end":773},{"source":1,"begin":767,"end":771},{"source":1,"begin":762,"end":765},{"source":1,"begin":758,"end":772},{"source":1,"begin":744,"end":756}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":275,"bytecodeCount":5,"sources":[{"source":1,"begin":796,"end":904},{"source":1,"begin":873,"end":897},{"source":1,"begin":891,"end":896}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":415,"bytecodeCount":9,"sources":[{"source":1,"begin":1637,"end":1714},{"source":1,"begin":1703,"end":1708},{"source":1,"begin":1692,"end":1708},{"source":1,"begin":1682,"end":1714}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":284,"bytecodeCount":6,"sources":[{"source":1,"begin":873,"end":897},{"source":1,"begin":868,"end":871},{"source":1,"begin":861,"end":898},{"source":1,"begin":851,"end":904}]}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite","returnbufOffset":"0","returnValueSym":{"#class":"vc.data.TACSymbol.Var.Full","namePrefix":"CANON11!!26","tag":{"#class":"tac.Tag.Bit256"},"callIndex":0,"meta":[{"key":{"name":"tac.storage.non-indexed-path.family","type":"analysis.storage.StorageAnalysisResult$StoragePaths","erasureStrategy":"Canonical"},"value":{"storagePaths":[{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}]}},{"key":{"name":"Tac.symbol.keyword","type":"vc.data.TACSymbol$Var$KeywordEntry","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.TACSymbol.Var.KeywordEntry.TACKeywordEntry","name":"L","maybeTACKeywordOrdinal":45}},{"key":{"name":"tac.scalarization.sort","type":"vc.data.ScalarizationSort","erasureStrategy":"Canonical"},"value":{"#class":"vc.data.ScalarizationSort.Split","idx":"0"}},{"key":{"name":"tac.storage.bit-width","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":256},{"key":{"name":"tac.storage.pretty.paths","type":"analysis.storage.DisplayPaths","erasureStrategy":"Erased"},"value":{"paths":[{"#class":"analysis.storage.DisplayPath.FieldAccess","field":"num","base":{"#class":"analysis.storage.DisplayPath.Root","name":"ms"}}]}},{"key":{"name":"tac.slot.type","type":"spec.cvlast.typedescriptors.EVMTypeDescriptor$EVMValueType","erasureStrategy":"Canonical"},"value":{"#class":"spec.cvlast.typedescriptors.EVMTypeDescriptor.UIntK","bitwidth":256}},{"key":{"name":"tac.storage.non-indexed-path","type":"analysis.storage.StorageAnalysisResult$NonIndexedPath","erasureStrategy":"Canonical"},"value":{"#class":"analysis.storage.StorageAnalysisResult.NonIndexedPath.Root","slot":"0"}},{"key":{"name":"tac.storage","type":"java.math.BigInteger","erasureStrategy":"Canonical"},"value":"ce4604a0000000000000000000000001"},{"key":{"name":"tac.stack.height","type":"java.lang.Integer","erasureStrategy":"Canonical"},"value":1015}]},"callIndex":1}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":269,"bytecodeCount":6,"sources":[{"source":1,"begin":710,"end":773},{"source":1,"begin":620,"end":783},{"source":1,"begin":574,"end":790}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":311,"bytecodeCount":6,"sources":[{"source":1,"begin":1100,"end":1217},{"source":1,"begin":1054,"end":1224}]}}
		AnnotationCmd:78 JSON{"key":{"name":"block.source","type":"decompiler.Decompiler$BlockSourceInfo","erasureStrategy":"Canonical"},"value":{"contractName":"Basic","contractAddress":"ce4604a0000000000000000000000001","contractBytecodeHash":"eae8a77222aee4d4b583821fc0a6f7f9a524894a8d7dfaec6f225568cead6841","pc":87,"bytecodeCount":8,"sources":[{"source":0,"begin":115,"end":265}]}}
		AssignExpCmd:79 lastReverted:5 false
		AnnotationCmd:79 JSON{"key":{"name":"tac.return.path","type":"tac.MetaMap$Companion$NothingValue","erasureStrategy":"Canonical"},"value":{}}
		AnnotationCmd:56 JSON{"key":{"name":"snippet.cmd","type":"vc.data.SnippetCmd","erasureStrategy":"CallTrace"},"value":{"#class":"vc.data.SnippetCmd.EVMSnippetCmd.HaltSnippet.Return","range":{"specFile":"Basic.sol","start":{"line":10,"charByteOffset":4},"end":{"line":15,"charByteOffset":5}}}}
		LabelCmd:56 "End procedure Basic-getMyStruct(bool)"
		LabelCmd:56 "2: Write struct..."
		LabelCmd:56 "1: Write field num for variable myStruct101:Basic.MyStruct..."
		LabelCmd:56 "0: Move primitive value for variable tacTmp!fieldnum104:int..."
		LabelCmd:56 "...done 0"
		LabelCmd:56 "...done 1"
		AssignExpCmd CANON6!!29:8 CANON11!!26:80
		LabelCmd:56 "...done 2"
		AnnotationCmd:56 JSON{"key":{"name":"call.trace.pop","type":"analysis.icfg.Inliner$CallStack$PopRecord","erasureStrategy":"Canonical"},"value":{"callee":{"contractId":"ce4604a0000000000000000000000001","sigHash":{"n":"3fe67f11"},"attr":{"#class":"scene.MethodAttribute.Common"}},"calleeId":1}}
	}
	Block 1_0_0_1_0_7 Succ [136_1021_0_1_0_0] {
		LabelCmd "Parallel assignment for CANON11!!26:bv256 := CANON11!!3:bv256"
		AssignExpCmd CANON11!!26:6 CANON11!!3:6
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
        "name": "tac.wordmap-key",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "0"
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "0"
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
        "name": "tac.calldata.offset",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "0"
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
  "3": [
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
        "name": "tac.wordmap-key",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "4"
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "4"
      }
    },
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
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.calldata.offset",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "4"
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
  "4": [
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
  "5": [
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
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
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
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "0"
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
        "name": "tac.storage.pretty.paths",
        "type": "analysis.storage.DisplayPaths",
        "erasureStrategy": "Erased"
      },
      "value": {
        "paths": [
          {
            "#class": "analysis.storage.DisplayPath.FieldAccess",
            "field": "num",
            "base": {
              "#class": "analysis.storage.DisplayPath.Root",
              "name": "ms"
            }
          }
        ]
      }
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
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
        "slot": "0"
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
  "8": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "Basic.MyStruct",
          "fields": [
            {
              "fieldName": "num",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "num",
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
          "specFile": "Basic.spec",
          "start": {
            "line": 7,
            "charByteOffset": 4
          },
          "end": {
            "line": 7,
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
      "value": "myStruct.num"
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
        "name": "tacCalldatasize",
        "maybeTACKeywordOrdinal": 12
      }
    }
  ],
  "10": [
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
  "11": [
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
  "12": [
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
  "13": [
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
  "14": [
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
  "15": [
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
  "16": [
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
      "value": "Basic"
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
  "17": [
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
  "18": [
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
  "19": [
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
  "20": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "Basic.MyStruct",
          "fields": [
            {
              "fieldName": "num",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "num",
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
    },
    {
      "key": {
        "name": "cvl.display",
        "type": "java.lang.String",
        "erasureStrategy": "CallTrace"
      },
      "value": "myStruct"
    }
  ],
  "21": [
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
      "value": 1019
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
      "value": 1017
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
  "26": [
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
  "27": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "Basic.MyStruct",
          "fields": [
            {
              "fieldName": "num",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "num",
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
  "28": [
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
          "specFile": "Basic.spec",
          "start": {
            "line": 6,
            "charByteOffset": 4
          },
          "end": {
            "line": 6,
            "charByteOffset": 11
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
      "value": "b"
    }
  ],
  "29": [
    {
      "key": {
        "name": "cvl.type",
        "type": "spec.cvlast.CVLType$PureCVLType",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract",
        "name": {
          "name": "Basic"
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
  "30": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 0
    }
  ],
  "31": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1
    }
  ],
  "32": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 2
    }
  ],
  "33": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 3
    }
  ],
  "34": [
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
      "value": "Basic"
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
  "35": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 4
    }
  ],
  "36": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 5
    }
  ],
  "37": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 6
    }
  ],
  "38": [
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
  "39": [
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
  "40": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 7
    }
  ],
  "41": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 8
    }
  ],
  "42": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 9
    }
  ],
  "43": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 10
    }
  ],
  "44": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 11
    }
  ],
  "45": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 12
    }
  ],
  "46": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 13
    }
  ],
  "47": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 14
    }
  ],
  "48": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 15
    }
  ],
  "49": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 16
    }
  ],
  "50": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 17
    }
  ],
  "51": [
    {
      "key": {
        "name": "cvl.label.start.id",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 18
    }
  ],
  "52": [
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
        "specFile": "Basic.spec",
        "start": {
          "line": 6,
          "charByteOffset": 4
        },
        "end": {
          "line": 6,
          "charByteOffset": 11
        }
      }
    }
  ],
  "53": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "Basic.spec",
        "start": {
          "line": 6,
          "charByteOffset": 4
        },
        "end": {
          "line": 6,
          "charByteOffset": 11
        }
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "55": [
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
          "id": "b",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 0
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
              "specFile": "Basic.spec",
              "start": {
                "line": 7,
                "charByteOffset": 42
              },
              "end": {
                "line": 7,
                "charByteOffset": 43
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "56": [
    {
      "key": {
        "name": "cvl.range",
        "type": "utils.Range",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "#class": "utils.Range.Range",
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "57": [
    {
      "key": {
        "name": "tac.immutable.array",
        "type": "vc.data.ImmutableBound",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "sym": {
          "#class": "vc.data.TACSymbol.Var.Full",
          "namePrefix": "tacCalldatasize!!15",
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
  "58": [
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
        "name": "tac.wordmap-key",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "4"
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "4"
      }
    },
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
        "name": "tac.storage.bit-width",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 256
    },
    {
      "key": {
        "name": "tac.calldata.offset",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "4"
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
  "59": [
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
      "value": "Basic"
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
  "60": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 11,
        "source": 0,
        "begin": 25,
        "len": 242,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "61": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 25,
        "source": 0,
        "begin": 25,
        "len": 242,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "62": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 42,
        "source": 0,
        "begin": 25,
        "len": 242,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "63": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 68,
        "source": 0,
        "begin": 115,
        "len": 150,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "64": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 73,
        "source": 0,
        "begin": 115,
        "len": 150,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "65": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 103,
        "source": 0,
        "begin": 162,
        "len": 15,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "66": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 110,
        "source": 0,
        "begin": 189,
        "len": 51,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "67": [
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
        "specFile": "Basic.spec",
        "start": {
          "line": 8,
          "charByteOffset": 4
        },
        "end": {
          "line": 8,
          "charByteOffset": 28
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
          "#class": "spec.cvlast.CVLExp.FieldSelectExp",
          "structExp": {
            "#class": "spec.cvlast.CVLExp.VariableExp",
            "id": "myStruct",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 0
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
                "name": "Basic.MyStruct",
                "fields": [
                  {
                    "fieldName": "num",
                    "cvlType": {
                      "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                      "k": 256
                    }
                  }
                ]
              },
              "range": {
                "#class": "utils.Range.Range",
                "specFile": "Basic.spec",
                "start": {
                  "line": 8,
                  "charByteOffset": 11
                },
                "end": {
                  "line": 8,
                  "charByteOffset": 19
                }
              }
            },
            "twoStateIndex": "NEITHER"
          },
          "fieldName": "num",
          "tag": {
            "scope": {
              "scopeStack": [
                {
                  "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                },
                {
                  "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                  "scopeId": 0
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
              "specFile": "Basic.spec",
              "start": {
                "line": 8,
                "charByteOffset": 11
              },
              "end": {
                "line": 8,
                "charByteOffset": 23
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
        "specFile": "Basic.spec",
        "start": {
          "line": 8,
          "charByteOffset": 4
        },
        "end": {
          "line": 8,
          "charByteOffset": 28
        }
      }
    }
  ],
  "69": [
    {
      "key": {
        "name": "cvl.struct.path",
        "type": "spec.cvlast.CVLStructPathNode",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "rootStructType": {
          "name": "Basic.MyStruct",
          "fields": [
            {
              "fieldName": "num",
              "cvlType": {
                "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                "k": 256
              }
            }
          ]
        },
        "fields": [
          {
            "fieldName": "num",
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
          "specFile": "Basic.spec",
          "start": {
            "line": 7,
            "charByteOffset": 4
          },
          "end": {
            "line": 7,
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
      "value": "myStruct"
    }
  ],
  "70": [
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
                  "scopeId": 0
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
              "specFile": "Basic.spec",
              "start": {
                "line": 8,
                "charByteOffset": 26
              },
              "end": {
                "line": 8,
                "charByteOffset": 27
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
        "specFile": "Basic.spec",
        "start": {
          "line": 8,
          "charByteOffset": 4
        },
        "end": {
          "line": 8,
          "charByteOffset": 28
        }
      }
    }
  ],
  "71": [
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
            "#class": "spec.cvlast.CVLExp.FieldSelectExp",
            "structExp": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "myStruct",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 0
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
                  "name": "Basic.MyStruct",
                  "fields": [
                    {
                      "fieldName": "num",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    }
                  ]
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "Basic.spec",
                  "start": {
                    "line": 8,
                    "charByteOffset": 11
                  },
                  "end": {
                    "line": 8,
                    "charByteOffset": 19
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "num",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 0
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
                "specFile": "Basic.spec",
                "start": {
                  "line": 8,
                  "charByteOffset": 11
                },
                "end": {
                  "line": 8,
                  "charByteOffset": 23
                }
              }
            }
          },
          "r": {
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
                    "scopeId": 0
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
                "specFile": "Basic.spec",
                "start": {
                  "line": 8,
                  "charByteOffset": 26
                },
                "end": {
                  "line": 8,
                  "charByteOffset": 27
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
                  "scopeId": 0
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
              "specFile": "Basic.spec",
              "start": {
                "line": 8,
                "charByteOffset": 11
              },
              "end": {
                "line": 8,
                "charByteOffset": 27
              }
            }
          }
        },
        "o1": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "CANON6!!29",
            "tag": {
              "#class": "tac.Tag.Int"
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
                    "name": "Basic.MyStruct",
                    "fields": [
                      {
                        "fieldName": "num",
                        "cvlType": {
                          "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                          "k": 256
                        }
                      }
                    ]
                  },
                  "fields": [
                    {
                      "fieldName": "num",
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
                    "specFile": "Basic.spec",
                    "start": {
                      "line": 7,
                      "charByteOffset": 4
                    },
                    "end": {
                      "line": 7,
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
                "value": "myStruct"
              }
            ]
          },
          "exp": {
            "#class": "spec.cvlast.CVLExp.FieldSelectExp",
            "structExp": {
              "#class": "spec.cvlast.CVLExp.VariableExp",
              "id": "myStruct",
              "tag": {
                "scope": {
                  "scopeStack": [
                    {
                      "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                    },
                    {
                      "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                      "scopeId": 0
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
                  "name": "Basic.MyStruct",
                  "fields": [
                    {
                      "fieldName": "num",
                      "cvlType": {
                        "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK",
                        "k": 256
                      }
                    }
                  ]
                },
                "range": {
                  "#class": "utils.Range.Range",
                  "specFile": "Basic.spec",
                  "start": {
                    "line": 8,
                    "charByteOffset": 11
                  },
                  "end": {
                    "line": 8,
                    "charByteOffset": 19
                  }
                }
              },
              "twoStateIndex": "NEITHER"
            },
            "fieldName": "num",
            "tag": {
              "scope": {
                "scopeStack": [
                  {
                    "#class": "spec.cvlast.CVLScope.Item.AstScopeItem"
                  },
                  {
                    "#class": "spec.cvlast.CVLScope.Item.RuleScopeItem",
                    "scopeId": 0
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
                "specFile": "Basic.spec",
                "start": {
                  "line": 8,
                  "charByteOffset": 11
                },
                "end": {
                  "line": 8,
                  "charByteOffset": 23
                }
              }
            }
          }
        },
        "o2": {
          "out": {
            "#class": "vc.data.TACSymbol.Var.Full",
            "namePrefix": "CANON16",
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
                  "#class": "spec.cvlast.CVLType.PureCVLType.Primitive.NumberLiteral",
                  "n": "0"
                }
              }
            ]
          },
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
                    "scopeId": 0
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
                "specFile": "Basic.spec",
                "start": {
                  "line": 8,
                  "charByteOffset": 26
                },
                "end": {
                  "line": 8,
                  "charByteOffset": 27
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
        "specFile": "Basic.spec",
        "start": {
          "line": 8,
          "charByteOffset": 4
        },
        "end": {
          "line": 8,
          "charByteOffset": 28
        }
      }
    }
  ],
  "72": [
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
        "specFile": "Basic.spec",
        "start": {
          "line": 8,
          "charByteOffset": 4
        },
        "end": {
          "line": 8,
          "charByteOffset": 28
        }
      }
    }
  ],
  "73": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 126,
        "source": 0,
        "begin": 219,
        "len": 10,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "74": [
    {
      "key": {
        "name": "tac.meta",
        "type": "vc.data.TACMetaInfo",
        "erasureStrategy": "CallTrace"
      },
      "value": {
        "pc_": 395,
        "source": 1,
        "begin": 1520,
        "len": 9,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "75": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        ]
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
        "name": "L",
        "maybeTACKeywordOrdinal": 45
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "0"
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
        "name": "tac.storage.pretty.paths",
        "type": "analysis.storage.DisplayPaths",
        "erasureStrategy": "Erased"
      },
      "value": {
        "paths": [
          {
            "#class": "analysis.storage.DisplayPath.FieldAccess",
            "field": "num",
            "base": {
              "#class": "analysis.storage.DisplayPath.Root",
              "name": "ms"
            }
          }
        ]
      }
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
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
        "slot": "0"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    },
    {
      "key": {
        "name": "tac.stack.height",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1019
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
        "pc_": 134,
        "source": 0,
        "begin": 210,
        "len": 19,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
          },
          "sourceDir": ".post_autofinders.0"
        }
      }
    },
    {
      "key": {
        "name": "tac.storage.node",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        ]
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    },
    {
      "key": {
        "name": "tac.storage.access",
        "type": "tac.MetaMap$Companion$NothingValue",
        "erasureStrategy": "Canonical"
      },
      "value": {
      }
    },
    {
      "key": {
        "name": "tac.storage.printer",
        "type": "instrumentation.StoragePathAnnotation$StoragePathPrinter",
        "erasureStrategy": "Canonical"
      },
      "value": {
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
        "pc_": 165,
        "source": 0,
        "begin": 115,
        "len": 150,
        "jumpType": "EXIT",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
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
        "pc_": 86,
        "source": 0,
        "begin": 115,
        "len": 150,
        "jumpType": "ENTER",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
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
        "pc_": 95,
        "source": 0,
        "begin": 115,
        "len": 150,
        "jumpType": "REGULAR",
        "address": "ce4604a0000000000000000000000001",
        "sourceContext": {
          "indexToFilePath": {
            "0": "Basic.sol"
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
        "specFile": "Basic.spec",
        "start": {
          "line": 7,
          "charByteOffset": 4
        },
        "end": {
          "line": 7,
          "charByteOffset": 45
        }
      }
    }
  ],
  "80": [
    {
      "key": {
        "name": "tac.storage.non-indexed-path.family",
        "type": "analysis.storage.StorageAnalysisResult$StoragePaths",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "storagePaths": [
          {
            "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
            "slot": "0"
          }
        ]
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
        "name": "L",
        "maybeTACKeywordOrdinal": 45
      }
    },
    {
      "key": {
        "name": "tac.scalarization.sort",
        "type": "vc.data.ScalarizationSort",
        "erasureStrategy": "Canonical"
      },
      "value": {
        "#class": "vc.data.ScalarizationSort.Split",
        "idx": "0"
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
        "name": "tac.storage.pretty.paths",
        "type": "analysis.storage.DisplayPaths",
        "erasureStrategy": "Erased"
      },
      "value": {
        "paths": [
          {
            "#class": "analysis.storage.DisplayPath.FieldAccess",
            "field": "num",
            "base": {
              "#class": "analysis.storage.DisplayPath.Root",
              "name": "ms"
            }
          }
        ]
      }
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
        "#class": "analysis.storage.StorageAnalysisResult.NonIndexedPath.Root",
        "slot": "0"
      }
    },
    {
      "key": {
        "name": "tac.storage",
        "type": "java.math.BigInteger",
        "erasureStrategy": "Canonical"
      },
      "value": "ce4604a0000000000000000000000001"
    },
    {
      "key": {
        "name": "tac.stack.height",
        "type": "java.lang.Integer",
        "erasureStrategy": "Canonical"
      },
      "value": 1017
    }
  ]
}