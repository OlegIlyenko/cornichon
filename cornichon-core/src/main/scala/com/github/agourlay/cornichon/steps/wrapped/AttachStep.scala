package com.github.agourlay.cornichon.steps.wrapped

import com.github.agourlay.cornichon.core._

// Transparent Attach has no title - steps are flatten in the main execution
case class AttachStep(title: String = "", nested: List[Step]) extends WrapperStep {

  override def run(engine: Engine)(initialRunState: RunState) =
    engine.runSteps(nested, initialRunState)

}
