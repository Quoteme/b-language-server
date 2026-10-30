machine Bank_r1 refines Bank sees Bank_ctx

variables clients account_access blocked

invariants
  @inv1 blocked ⊆ clients

events
  event INITIALISATION extends INITIALISATION
    then
      @act2 blocked ≔ ∅
  end

  event add_client extends add_client
  end

  event connect_account refines connect_account
    any c a
    where
      @grd1 c ∈ clients ∖ blocked
      @grd2 a ∈ ACCOUNTS ∖ ran(account_access)
    then
      @act1 account_access ≔ account_access ∪ {c ↦ a}
  end

  event block
    any c
    where
      @grd1 c ∈ clients
    then
      @act1 blocked ≔ blocked ∪ {c}
  end
end
