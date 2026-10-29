machine Counter sees Counter_ctx
variables n
invariants
  @inv1 n ∈ 0 ‥ limit
  @inv2 n ≠ 3
variant limit − n
events
  event INITIALISATION
    then @act1 n ≔ 0
  end
  convergent event inc
    when @grd1 n < limit
    then @act1 n ≔ n + 1
  end
  event add
    any k
    where
      @grd1 k ∈ ℕ
      @grd2 n + k ≤ limit
    then @act1 n ≔ n + k
  end
  event check
    when @grd1 ∀x·x ∈ 0 ‥ n ⇒ x ≤ limit
    then @act1 n :∣ n' ∈ 0 ‥ limit
  end
end
