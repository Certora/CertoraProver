invariant balancesLargerThanZero(env e, uint i) i < balanceLength(e) => balances(e, i) > 0;

invariant balancesLargerThanZeroNoSanityFailure(env e, uint i) (balanceLength(e) == 0 || (i < balanceLength(e) => balances(e, i) > 0));

invariant privateBalancesLargerThanZero(env e, uint i) i < privateBalanceLength(e) => getPrivateBalances(e, i) > 0;

invariant privateBalancesLargerThanZeroNoSanityFailure(env e, uint i) (balanceLength(e) == 0 || (i < balanceLength(e) => balances(e, i) > 0));