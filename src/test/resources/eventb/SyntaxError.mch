machine SyntaxError

variables x

invariants
  @inv1 x ∈ ℕ ∧

events
  event INITIALISATION
    then
      @act1 x ≔ 0
  end
end
