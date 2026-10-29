MACHINE Doors
SETS
  DOOR = {north, south};
  PERSON
CONSTANTS
  capacity
PROPERTIES
  capacity : NAT1 & capacity = 3
DEFINITIONS
  full == (card(inside) = capacity)
VARIABLES
  open, inside
INVARIANT
  open <: DOOR & inside <: PERSON & card(inside) <= capacity
INITIALISATION
  open := {} || inside := {}
OPERATIONS
  open_door(d) = PRE d : DOOR & d /: open THEN open := open \/ {d} END;
  close_door(d) = PRE d : open THEN open := open - {d} END;
  enter(p) = PRE p : PERSON - inside & open /= {} & not(full) THEN inside := inside \/ {p} END;
  n <-- count = n := card(inside)
END
