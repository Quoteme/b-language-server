machine Counter_r1 refines Counter sees Counter_ctx
variables n
invariants
  @inv3 n ≤ limit
events
  event INITIALISATION extends INITIALISATION
  end
  convergent event inc refines inc
    when @grd1 n < limit
    then @act1 n ≔ n + 1
  end
  event add1 refines add
    when @grd1 n + 1 ≤ limit
    with @k k = 1
    then @act1 n ≔ n + 1
  end
end
