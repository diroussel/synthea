package org.mitre.synthea.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.world.agents.Person;

/** Behavioral history checks that also run against the original list implementation. */
public class PersonHistoryTest {
  private State state(String name, long exited) {
    State state = new State.Simple();
    state.name = name;
    state.exited = exited;
    return state;
  }

  @Test
  public void snapshotsPreserveLegacyListsAndSharedReferences() throws Exception {
    Person person = new Person(1);
    person.attributes.put("advance", false);
    Module module = TestHelper.getFixture("history_repeated_visits.json");
    assertFalse(module.process(person, 10, false));
    byte[] before = snapshot(person);
    assertFalse(module.process(person, 10, false));
    assertArrayEquals(before, snapshot(person));
    assertEquals(List.class, ObjectStreamClass.lookup(Person.class).getField("history").getType());
    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(before))) {
      Person loaded = (Person) input.readObject();
      assertTrue(loaded.hadPriorState("Initial"));
      assertSame(loaded.attributes.get(module.name), loaded.history);
      assertEquals(LinkedList.class, loaded.history.getClass());
      List<State> restored = loaded.history;
      assertFalse(module.process(loaded, 10, false));
      assertSame(restored, loaded.history);
      loaded.history.remove(0);
      assertTrue(loaded.hadPriorState("Initial"));
      loaded.history.remove(0);
      assertFalse(loaded.hadPriorState("Initial"));
    }
  }

  private byte[] snapshot(Person person) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(person);
    }
    return bytes.toByteArray();
  }

  @Test
  public void activationReusesHistoryAndKeepsModulesIsolated() throws Exception {
    Person person = new Person(1);
    person.attributes.put("advance", false);
    Module first = TestHelper.getFixture("history_repeated_visits.json");
    Module second = TestHelper.getFixture("history_repeated_visits.json");
    second.name = "Second Module";
    first.process(person, 10, false);
    List<State> history = person.history;
    State current = history.get(0);
    history.add(state("Visited", 1));
    second.process(person, 10, false);
    assertFalse(person.hadPriorState("Visited"));
    first.process(person, 10, false);
    assertSame(history, person.history);
    assertSame(current, person.history.get(0));
    assertTrue(person.hadPriorState("Visited"));
    assertEquals(3, person.history.size());
  }

  @Test
  public void repeatedVisitsAndListMutationsPreservePriorStateQueries() {
    Person person = new Person(1);
    person.history = new LinkedList<>();
    person.history.addAll(Arrays.asList(state("A", 3), state("A", 2), state("B", 1)));
    person.history.remove(0);
    assertTrue(person.hadPriorState("A"));
    person.history.set(0, state("C", 2));
    assertFalse(person.hadPriorState("A"));
    person.history.subList(0, 1).clear();
    assertFalse(person.hadPriorState("C"));
    person.history.clear();
    assertFalse(person.hadPriorState("B"));
  }

  @Test
  public void boundedQueriesRespectOrderAndTime() {
    Person person = new Person(1);
    person.history = new LinkedList<>();
    person.history.addAll(Arrays.asList(state("B", 3), state("A", 2), state("B", 1)));
    assertTrue(person.hadPriorState("A"));
    assertFalse(person.hadPriorState("A", "B", null));
    assertFalse(person.hadPriorState("A", null, 2L));
    assertTrue(person.hadPriorState("A", null, 1L));
  }
}
