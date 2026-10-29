machine Bank sees Bank_ctx

variables clients account_access

invariants
  @inv1 clients ⊆ PERSONS
  @inv2 account_access ∈ clients ↔ ACCOUNTS
  @inv3 card(clients) ≤ maxClients

events
  event INITIALISATION
    then
      @act1 clients, account_access ≔ ∅, ∅
  end

  event add_client
    any p
    where
      @grd1 p ∈ PERSONS ∖ clients
      @grd2 card(clients) < maxClients
    then
      @act1 clients ≔ clients ∪ {p}
  end

  event connect_account
    any c a
    where
      @grd1 c ∈ clients
      @grd2 a ∈ ACCOUNTS ∖ ran(account_access)
    then
      @act1 account_access ≔ account_access ∪ {c ↦ a}
  end
end
