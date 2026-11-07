package org.mitre.synthea.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Patient;
import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Components;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.TimeSeriesData;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Code;

/** Differential tests against the existing exporter, including exact primitive lexical values. */
public class DirectFhirR4Test {
  @Test
  public void generatedBundlesPreserveAllResourcesAndRelationships() throws Exception {
    TestHelper.loadTestProperties();
    TestHelper.getGeneratedPeople();
    boolean originalCore = FhirR4.USE_US_CORE_IG;
    boolean originalTransaction = FhirR4.TRANSACTION_BUNDLE;
    String originalVersion = FhirR4.US_CORE_VERSION;
    long stop = System.currentTimeMillis();
    try {
      for (boolean core : new boolean[] {false, true}) {
        for (boolean transaction : new boolean[] {false, true}) {
          FhirR4.USE_US_CORE_IG = core;
          FhirR4.US_CORE_VERSION = "6.1.0";
          FhirR4.useUSCore6();
          FhirR4.TRANSACTION_BUNDLE = transaction;
          Person[] baseline = TestHelper.getGeneratedPeople();
          Person[] direct = TestHelper.getGeneratedPeople();
          for (int i = 0; i < baseline.length; i++) {
            if (i == 0) {
              addClinicalFixtures(baseline[i]);
              addClinicalFixtures(direct[i]);
            }
            String expected = FhirR4.getContext().newJsonParser().setPrettyPrint(false)
                .encodeResourceToString(FhirR4.convertToFHIR(baseline[i], stop));
            StringWriter output = new StringWriter();
            FhirR4.writeDirectJson(direct[i], stop, output, i % 2 == 0);
            assertJsonEqual(expected, output.toString());
            assertEquals(baseline[i].getCount(), direct[i].getCount());
          }
        }
      }
    } finally {
      FhirR4.USE_US_CORE_IG = originalCore;
      FhirR4.TRANSACTION_BUNDLE = originalTransaction;
      FhirR4.US_CORE_VERSION = originalVersion;
      FhirR4.useUSCore3();
      FhirR4.useUSCore4();
      FhirR4.useUSCore5();
      FhirR4.useUSCore6();
      FhirR4.useUSCore7();
    }
  }

  @Test
  public void observationValuesProfilesAndEscapingMatch() throws Exception {
    boolean originalCore = FhirR4.USE_US_CORE_IG;
    String originalVersion = FhirR4.US_CORE_VERSION;
    String includes = Config.get("exporter.fhir.included_resources");
    String excludes = Config.get("exporter.fhir.excluded_resources");
    try {
      Config.set("exporter.fhir.included_resources", "");
      Config.set("exporter.fhir.excluded_resources", "");
      FhirR4.reloadIncludeExclude();
      for (String version : new String[] {"3", "4", "5", "6", "7"}) {
        FhirR4.USE_US_CORE_IG = true;
        FhirR4.US_CORE_VERSION = version;
        FhirR4.useUSCore3();
        FhirR4.useUSCore4();
        FhirR4.useUSCore5();
        FhirR4.useUSCore6();
        FhirR4.useUSCore7();
        for (Object value : new Object[] {123456.789, 0.000123456789, false,
            "quotes \" \\ newline\n café <tag>", new Code("SNOMED-CT", "1", "A & B"),
            sampledData(), null}) {
          Person person = new Person(42);
          HealthRecord.Observation observation = person.record.new Observation(1234567890000L,
              "test", value);
          observation.category = "exam";
          observation.unit = "mg";
          observation.codes.add(new Code("LOINC", "1234-5", "Test \"display\""));
          observation.codes.add(new Code("LOINC", "82810-3", "Additional"));
          final Bundle baseline = context();
          final Bundle directBundle = context();
          // The original mapper is exercised through the same conversion logic in the other test;
          // here use its FHIR value mapping and shared profile selection as an independent oracle.
          org.hl7.fhir.r4.model.Observation expected = new org.hl7.fhir.r4.model.Observation();
          expected.setId(observation.uuid.toString());
          expected.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
          expected.setSubject(new org.hl7.fhir.r4.model.Reference("urn:uuid:patient"));
          expected.setEncounter(new org.hl7.fhir.r4.model.Reference("urn:uuid:encounter"));
          expected.setCode(FhirR4.mapCodeToCodeableConcept(observation.codes.get(0), "LOINC"));
          expected.getCode().addCoding().setSystem("http://loinc.org")
              .setCode("82810-3").setDisplay("Additional");
          expected.addCategory().addCoding()
              .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
              .setCode("exam").setDisplay("Exam");
          if (version.equals("5")) {
            expected.addCategory().addCoding()
                .setSystem("http://hl7.org/fhir/us/core/CodeSystem/us-core-observation-category")
                .setCode("clinical-test").setDisplay("Clinical Test");
          }
          for (String profile : FhirR4.observationProfiles(observation)) {
            expected.getMeta().addProfile(profile);
          }
          expected.setEffective(new org.hl7.fhir.r4.model.DateTimeType(
              new java.util.Date(observation.start)));
          expected.setIssued(new java.util.Date(observation.start));
          expected.setValue(FhirR4.mapValueToFHIRType(value, observation.unit));
          FhirR4.newEntry(baseline, expected, observation.uuid.toString());
          DirectFhirR4 direct = new DirectFhirR4();
          direct.observation(directBundle, directBundle.getEntry().get(0),
              directBundle.getEntry().get(1), observation);
          StringWriter output = new StringWriter();
          direct.write(directBundle, output, false);
          assertJsonEqual(FhirR4.getContext().newJsonParser().encodeResourceToString(baseline),
              output.toString());
        }
      }
    } finally {
      FhirR4.USE_US_CORE_IG = originalCore;
      FhirR4.US_CORE_VERSION = originalVersion;
      FhirR4.useUSCore3();
      FhirR4.useUSCore4();
      FhirR4.useUSCore5();
      FhirR4.useUSCore6();
      FhirR4.useUSCore7();
      Config.set("exporter.fhir.included_resources", includes == null ? "" : includes);
      Config.set("exporter.fhir.excluded_resources", excludes == null ? "" : excludes);
      FhirR4.reloadIncludeExclude();
    }
  }

  @Test
  public void configSwitchFilteringAndWriterOwnership() throws Exception {
    String original = Config.get("exporter.fhir.direct_json", "false");
    String exclusions = Config.get("exporter.fhir.excluded_resources", "");
    long stop = System.currentTimeMillis();
    try {
      Config.set("exporter.fhir.excluded_resources", "Observation,Condition");
      FhirR4.reloadIncludeExclude();
      Config.set("exporter.fhir.direct_json", "false");
      String expected = FhirR4.convertToFHIRJson(TestHelper.getGeneratedPeople()[0], stop);
      Config.set("exporter.fhir.direct_json", "true");
      String actual = FhirR4.convertToFHIRJson(TestHelper.getGeneratedPeople()[0], stop);
      assertJsonEqual(expected, actual);
      TrackingWriter output = new TrackingWriter();
      FhirR4.writeDirectJson(TestHelper.getGeneratedPeople()[0], stop, output, false);
      assertFalse(output.closed);
      FailingWriter failure = new FailingWriter();
      assertThrows(IOException.class, () ->
          FhirR4.writeDirectJson(TestHelper.getGeneratedPeople()[0], stop, failure, false));
      assertFalse(failure.closed);
    } finally {
      Config.set("exporter.fhir.direct_json", original);
      Config.set("exporter.fhir.excluded_resources", exclusions);
      FhirR4.reloadIncludeExclude();
    }
  }

  private static class TrackingWriter extends StringWriter {
    private boolean closed;

    @Override
    public void close() {
      closed = true;
    }
  }

  private static class FailingWriter extends java.io.Writer {
    private boolean closed;

    @Override
    public void write(char[] value, int offset, int length) throws IOException {
      throw new IOException("Injected output failure");
    }

    @Override
    public void flush() {
      // There is no buffer in this test writer.
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static void addClinicalFixtures(Person person) {
    HealthRecord record = person.record;
    HealthRecord.Encounter encounter = record.encounters.get(record.encounters.size() - 1);
    long time = encounter.start;
    Code code = new Code("SNOMED-CT", "123", "A & B \"quoted\" \\ café");
    code.version = "2025";
    HealthRecord.Entry condition = record.new Entry(time, "condition");
    condition.codes.add(code);
    condition.stop = time + 1000;
    encounter.conditions.add(condition);
    HealthRecord.Procedure procedure = record.new Procedure(time, "procedure");
    procedure.codes.add(code);
    procedure.reasons.add(code);
    encounter.procedures.add(procedure);
    HealthRecord.Procedure unmatched = record.new Procedure(time, "procedure");
    unmatched.stop = 0;
    unmatched.codes.add(code);
    unmatched.reasons.add(new Code("SNOMED-CT", "unmatched", "No matching condition"));
    encounter.procedures.add(unmatched);
    HealthRecord.Device device = record.new Device(time, "device");
    device.codes.add(code);
    device.deviceIdentifier = "device-1";
    device.udi = "udi";
    device.manufactureTime = time - 1000;
    device.expirationTime = time + 1000;
    encounter.devices.add(device);
    HealthRecord.Supply supply = record.new Supply(time, "supply");
    supply.codes.add(code);
    supply.quantity = 3;
    encounter.supplies.add(supply);
    HealthRecord.Allergy allergy = record.new Allergy(time, "allergy");
    allergy.codes.add(code);
    allergy.allergyType = "intolerance";
    allergy.category = "unknown";
    allergy.stop = time + 1000;
    allergy.reactions = new java.util.HashMap<>();
    allergy.reactions.put(code, HealthRecord.ReactionSeverity.SEVERE);
    encounter.allergies.add(allergy);
    HealthRecord.Immunization immunization = record.new Immunization(time, "immunization");
    immunization.codes.add(new Code("CVX", "1", "Vaccine"));
    encounter.immunizations.add(immunization);
    for (boolean inline : new boolean[] {false, true}) {
      Components.Attachment attachment = new Components.Attachment();
      attachment.contentType = "image/png";
      attachment.title = "Image \"title\"";
      attachment.hash = "YQ==";
      attachment.data = inline ? "Y Q==\n" : null;
      attachment.url = inline ? null : "https://example.org/image.png";
      attachment.width = 1;
      attachment.height = 2;
      attachment.size = 1;
      HealthRecord.Observation media = record.new Observation(time, "image", attachment);
      media.codes.add(code);
      encounter.observations.add(media);
    }
    HealthRecord.Observation parent = record.new Observation(time, "panel", null);
    parent.category = "laboratory";
    parent.codes.add(new Code("LOINC", "1234-5", "Panel"));
    parent.observations = new java.util.ArrayList<>();
    HealthRecord.Observation component = record.new Observation(time, "component", 0.00012345678);
    component.codes.add(new Code("LOINC", "5678-9", "Component"));
    parent.observations.add(component);
    encounter.observations.add(parent);
    HealthRecord.Report report = record.new Report(time, "report", java.util.List.of(parent));
    report.codes.add(new Code("LOINC", "1234-5", "Report"));
    encounter.reports.add(report);
  }

  private static Bundle context() {
    Bundle bundle = new Bundle().setType(Bundle.BundleType.COLLECTION);
    BundleEntryComponent patient = bundle.addEntry().setFullUrl("urn:uuid:patient");
    patient.setResource(new Patient().setId("patient"));
    bundle.addEntry().setFullUrl("urn:uuid:encounter")
        .setResource(new org.hl7.fhir.r4.model.Encounter().setId("encounter"));
    return bundle;
  }

  private static Components.SampledData sampledData() {
    Components.SampledData data = new Components.SampledData();
    data.originValue = 0.0;
    data.factor = 0.01;
    data.lowerLimit = -1.5;
    data.upperLimit = 1.5;
    TimeSeriesData series = new TimeSeriesData(3, 0.01);
    series.addValue(1.0);
    series.addValue(2.0);
    series.addValue(3.0);
    data.series = java.util.Collections.singletonList(series);
    return data;
  }

  private static void assertJsonEqual(String expected, String actual) throws IOException {
    try (com.fasterxml.jackson.core.JsonParser parser = new com.fasterxml.jackson.core.JsonFactory()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .createParser(actual)) {
      while (parser.nextToken() != null) {
        // Parse every token to detect malformed JSON and duplicate object members.
      }
    }
    String difference = difference(JsonParser.parseString(expected), JsonParser.parseString(actual),
        "");
    assertTrue(difference, difference.isEmpty());
  }

  private static String difference(JsonElement expected, JsonElement actual, String path) {
    if (expected == null || actual == null) {
      return expected == actual ? "" : path + " missing member";
    }
    if (expected.isJsonObject() && actual.isJsonObject()) {
      Set<String> keys = new HashSet<>(expected.getAsJsonObject().keySet());
      keys.addAll(actual.getAsJsonObject().keySet());
      for (String key : keys) {
        String result = difference(expected.getAsJsonObject().get(key),
            actual.getAsJsonObject().get(key), path + "/" + key);
        if (!result.isEmpty()) {
          return result;
        }
      }
      return "";
    }
    if (expected.isJsonArray() && actual.isJsonArray()) {
      if (expected.getAsJsonArray().size() != actual.getAsJsonArray().size()) {
        return path + " array size differs";
      }
      for (int i = 0; i < expected.getAsJsonArray().size(); i++) {
        String result = difference(expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i),
            path + "/" + i);
        if (!result.isEmpty()) {
          return result;
        }
      }
      return "";
    }
    return expected.toString().equals(actual.toString()) ? ""
        : path + " expected " + expected + " actual " + actual;
  }
}
