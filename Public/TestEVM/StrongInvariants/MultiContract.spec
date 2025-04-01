using ContractWithDelegate as contractWithDelegate;
using MultiContract as multi;

strong invariant storageValueIsConstant(env e) 1 == multi.storageValue;

strong invariant storageValueIsConstantNonCurrentContract(env e) 2 == contractWithDelegate.storageValueNonCurrentContract filtered{ f -> f.contract == contractWithDelegate }
