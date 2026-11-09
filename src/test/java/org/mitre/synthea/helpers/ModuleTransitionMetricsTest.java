package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Module;
import org.mitre.synthea.world.agents.Person;

/** Exercise real module transitions, including repeated state visits. */
public class ModuleTransitionMetricsTest {
  private boolean original;

  @Before
  public void setup() {
    original = TransitionMetrics.enabled;
    TransitionMetrics.clear();
  }

  @After
  public void restore() {
    TransitionMetrics.enabled = original;
    TransitionMetrics.clear();
  }

  @Test
  public void enabledMetricsCountRepeatVisitsAndDisabledMetricsDoNotRecord() throws Exception {
    Module module = TestHelper.getFixture("history_repeated_visits.json");
    Person person = new Person(1);
    person.attributes.put("advance", false);
    TransitionMetrics.enabled = true;
    assertFalse(module.process(person, 10, false));
    person.attributes.put("advance", true);
    assertFalse(module.process(person, 20, false));
    TransitionMetrics.Metric metric = TransitionMetrics.getMetric(module.name, "Wait");
    assertEquals(2, metric.entered.get());
    assertEquals(1, metric.population.get());
    assertEquals(1, metric.current.get());
    assertEquals(10, metric.duration.get());
    assertEquals(1, metric.destinations.get("Reset").get());
    TransitionMetrics.clear();
    TransitionMetrics.enabled = false;
    person.attributes.put("advance", true);
    assertFalse(module.process(person, 30, false));
    assertEquals(0, TransitionMetrics.getMetric(module.name, "Wait").entered.get());
  }
}
