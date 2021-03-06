

(comment
  Synthesizable fragments are either expressions or structures.

  Expressions either are immediates or are values.

  All expressions have types and error info.

  The details of a value expression are: the name of the verilog
  module it links to & that module's portspec, the function
  which implements its functionality, its walkable
  arguments, and its other arguments.

  The details of an immediate expression are: the piplin
  instance of it, and the function that converts it
  to verilog. Instead of a piplin instance, it could instead
  have a port usage.

  The details of a structure are the inputs (themselves exprs),
  the output/feedback regs (with exprs whose termini must be
  immediates)
  )

(comment
  Simulation:
  We have a datastructure containing the value of every register
  We run all the functions to get the new values of registers on
  which there is data available.
  Repeat.
  )

(comment
  Structures serve as barriers
  i.e. it produces a result no faster than the longest pipline in it.

  Many primitives are only valid when their inputs are valid. How do
  we communicate validity? With 

  Suppose not: does it become hard to reason about how the data flows?
  Needs a priority encoder/round robin primitive--when 2 things could
  happen, pick one.

  I am thinking now to have structural blocks serve as synchronization
  points, and then provide the priority/round-robin bypass/merging
  combinators.
  )

(comment
  We can propagate metadata via a macro:
  enforces that metadata threading points are in the same lexical scope

  (let ({:keys [:pipe :key :key2]}) (metapipe {:key val :key2 val}
                  (pipeline (exprs key) key2))
    body...)
  )

(comment
  I'm trying to figure out how to write code w/ valid and stall bits
  effectively programmed. This example is for an ALU w/ an intmath
  pipeline that computes +, -, *, and /, and a cordic processor that
  computes sin and cos.)

(defn prioritymerge [x y]
  (if-not (= (type x) (type y))
    (error "Can only merge data of same type")
    ;if x is valid, returns valid x
    ;if y is valid and x isn't, returns valid y
    ;if x and y are invalid, returns invalid
    (let [pass (valid? x)]
      (if pass
        x
        ;stall-with is like guard-with, but adds logic
        ;to the backpressure path
        ;stall-with is implemented by pushing the current
        ;stall expr onto the stall stack, and then making
        ;the new stall expr the bit-and of the argument
        ;and the current stall expr
        (stall-with pass y)))))

(defn mkalu [data op]
  (:out
    ;structural must automagically apply backpressure to
    ;any input that is valid? if is any input that isn't
    ;yet valid
    (structural [:in data op :out results (aluresult nil)]
      (let [intmath-op (isany? op #{:+ :- :* :/})
            cordic-op (bit-not intmath-op)
            ;isany? and bit-not can be used in guarded-with clauses
            ;since they can be synthesized as combinational logic
            ;noncombinational logic cannot be used as the guard
            intpipe (mkintmath op
                               (guarded-with intmath-op
                                             data))
            ;the expr (valid? [guard]) is 
            cordicpipe (mkcordic op
                                 (guarded-with cordic-op
                                               data))]
        ;merge functions do arbitration
        (connect results
                 (priority-merge intpipe cordicpipe))
        (backpressure (or (and intmath-op (ready? intpipe))
                          (and cordic-op (ready? cordic-pipe))))))))

(defn mkalu-no-guards [data op]
  (:out
    (structural [:in data op :out results (aluresult nil)]
      (let [intmath-op (isany? op #{:+ :- :* :/})
            cordic-op (bit-not intmath-op)
            intpipe (guarded-with intmath-op
                                  (mkintmath op data))
            cordicpipe (guarded-with cordic-op
                                     (mkcordic op data))]
        (connect results
                 (priority-merge intpipe cordicpipe))
        (backpressure (or (and intmath-op (ready? intpipe))
                          (and cordic-op (ready? cordic-pipe))))))))

(comment
  Given the power of guard-with and stall-with, we only need one more
  piece, and then even structural can be defined in terms of it:
  feedback. This form will be used to connect a loop. It is the only
  type of mandatory pipeline reg, but it could be flexibly determined
  where in the feedback loop the pipeline reg is placed.

  (feedback
    [a 0
     (inc a)]
    [b init-val
     (f b)]) ;=> a map of the feedback outputs

  is the feedback primitive. Hierarchy flattening allows for synthesis.
  )

(defn mkmult [x y]
  "by repeated addition, requires that x-in not change during computation"
  (:out
    (structural [:in x-in y-in ;inputs are guarded with the and of their valids
                 :out r (instance (type x) nil) remain (instance (type y) nil)]
      (let [done (= remain 0)]
        (connect remain
                 (if-not done
                   (dec remain)
                   y-in)) ;y-in is already guarded-with (valid? x) as well
        (connect r ;can't compute if x-in isn't held; perhaps should save x?
                 (if-not done
                   (+ r x-in)
                   x-in))
        (backpressure (not done))))))

(defn mkmult-better [x y]
  "by repeated addition, uses x-in to stall the previous stage"
  (:out
    (structural [:in x-in y-in ;inputs are guarded with the and of their valids
                 :out r (instance (type x) nil) remain (instance (type y) nil)]
      (let [done (= remain 0)]
        (connect remain
                 (priority-merge (dec remain))
                 y-in) ;y-in is already guarded-with (valid? x) as well
        (connect r ;can't compute if x-in isn't held; perhaps should save x?
                 (if done
                   x-in
                   (+ r x-in)))
        (backpressure ((not done))))))
