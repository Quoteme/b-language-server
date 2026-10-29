context Bank_ctx
sets
  PERSONS
  ACCOUNTS
constants
  maxClients
axioms
  @axm1 maxClients ∈ ℕ1
  @axm2 finite(PERSONS)
end
