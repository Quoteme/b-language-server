machine TypeErrors sees Bank_ctx

variables count owner

invariants
  @inv1 count ∈ ℕ
  @inv2 owner ∈ PERSONS

events
  event INITIALISATION
    then
      @act1 count ≔ 0
      @act2 owner :∈ PERSONS
  end

  event mix
    then
      @act1 count ≔ owner
  end

  event typo
    where
      @grd1 cuont > 0
    then
      @act1 count ≔ count + 1
  end
end
