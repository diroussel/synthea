package org.mitre.synthea.export;

import ca.uhn.fhir.parser.IParser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Base64BinaryType;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.ImagingStudy;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Media;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.SupplyDelivery;
import org.mitre.synthea.engine.Components.Attachment;
import org.mitre.synthea.engine.Components;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord;

/**
 * Experimental streaming R4 writer. Common clinical resources are written from HealthRecord
 * entries, without building their FHIR datatype graphs or using reflective serialization.
 * Small resource shells retain entry identity for claims, reports and provenance. The shells
 * never escape the private conversion path. Remaining resource mappings use the existing parser.
 * Each export owns its writer and dispatch table; the JsonFactory is safe to share.
 */
final class DirectFhirR4 {
  private static final JsonFactory JSON = new JsonFactory();
  private static final String SNOMED = "http://snomed.info/sct";
  private static final String LOINC = "http://loinc.org";
  private static final String UCUM = "http://unitsofmeasure.org";
  private static final String CORE = "http://hl7.org/fhir/us/core/StructureDefinition/";
  private final Map<Resource, ResourceWriter> resources = new IdentityHashMap<>();

  @FunctionalInterface
  private interface ResourceWriter {
    void write(JsonGenerator json) throws IOException;
  }

  void condition(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      Person person, HealthRecord.Entry condition) {
    Code code = condition.codes.get(0);
    CodeableConcept coded = FhirR4.mapCodeToCodeableConcept(code, SNOMED);
    FhirR4.addTranslation(code, coded, person);
    // Keep only the code for subsequent reason-reference lookup; all other fields stream below.
    Condition shell = new Condition().setCode(coded);
    String conditionProfile = FhirR4.useUSCore5() || FhirR4.useUSCore6() || FhirR4.useUSCore7()
        ? "us-core-condition-encounter-diagnosis" : "us-core-condition";
    add(bundle, shell, condition, json -> {
      profile(json, conditionProfile);
      json.writeObjectFieldStart("clinicalStatus");
      categoryContents(json, condition.stop == 0 ? "active" : "resolved",
          "http://terminology.hl7.org/CodeSystem/condition-clinical", null);
      json.writeEndObject();
      json.writeObjectFieldStart("verificationStatus");
      categoryContents(json, "confirmed",
          "http://terminology.hl7.org/CodeSystem/condition-ver-status", null);
      json.writeEndObject();
      if (FhirR4.USE_US_CORE_IG) {
        json.writeArrayFieldStart("category");
        category(json, "encounter-diagnosis",
            "http://terminology.hl7.org/CodeSystem/condition-category", "Encounter Diagnosis");
        json.writeEndArray();
      }
      json.writeFieldName("code");
      conceptItem(json, coded);
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      dateTime(json, "onsetDateTime", condition.start);
      if (condition.stop != 0) {
        dateTime(json, "abatementDateTime", condition.stop);
      }
      dateTime(json, "recordedDate", condition.start);
    });
  }

  void procedure(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      Person person, HealthRecord.Procedure procedure) {
    Code reason = procedure.reasons.isEmpty() ? null : procedure.reasons.get(0);
    BundleEntryComponent reasonEntry = reason == null ? null
        : FhirR4.findConditionResourceByCode(bundle, reason.code);
    CodeableConcept reasonCode = reason != null && reasonEntry == null
        ? FhirR4.mapCodeToCodeableConcept(reason, SNOMED) : null;
    if (reasonCode != null) {
      FhirR4.addTranslation(reason, reasonCode, person);
    }
    add(bundle, new Procedure(), procedure, json -> {
      profile(json, "us-core-procedure");
      field(json, "status", "completed");
      concept(json, "code", procedure.codes.get(0), SNOMED);
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      if (procedure.stop != 0) {
        json.writeObjectFieldStart("performedPeriod");
        dateTime(json, "start", procedure.start);
        dateTime(json, "end", procedure.stop);
        json.writeEndObject();
      } else {
        dateTime(json, "performedDateTime", procedure.start);
      }
      if (reasonEntry != null) {
        json.writeArrayFieldStart("reasonReference");
        referenceItem(json, reasonEntry.getFullUrl(), reason.display);
        json.writeEndArray();
      } else if (reasonCode != null) {
        json.writeArrayFieldStart("reasonCode");
        conceptItem(json, reasonCode);
        json.writeEndArray();
      }
      location(json, encounter);
    });
  }

  void report(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      HealthRecord.Report report) {
    boolean labsOnly = report.observations.stream()
        .allMatch(observation -> "laboratory".equalsIgnoreCase(observation.category));
    add(bundle, new DiagnosticReport(), report, json -> {
      if (labsOnly) {
        profile(json, "us-core-diagnosticreport-lab");
      }
      field(json, "status", "final");
      if (labsOnly) {
        json.writeArrayFieldStart("category");
        category(json, "LAB", "http://terminology.hl7.org/CodeSystem/v2-0074", "Laboratory");
        json.writeEndArray();
      }
      concept(json, "code", report.codes.get(0), LOINC);
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      dateTime(json, "effectiveDateTime", report.start);
      field(json, "issued", new InstantType(new Date(report.start)).getValueAsString());
      if (labsOnly && FhirR4.USE_US_CORE_IG) {
        Reference provider = ((org.hl7.fhir.r4.model.Encounter) encounter.getResource())
            .getServiceProvider();
        json.writeArrayFieldStart("performer");
        referenceItem(json, provider.getReference(), provider.getDisplay());
        json.writeEndArray();
      }
      if (FhirR4.shouldExport(org.hl7.fhir.r4.model.Observation.class)
          && !report.observations.isEmpty()) {
        json.writeArrayFieldStart("result");
        for (Observation observation : report.observations) {
          referenceItem(json, observation.fullUrl, observation.codes.get(0).display);
        }
        json.writeEndArray();
      }
    });
  }

  void imagingStudy(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      HealthRecord.ImagingStudy study) {
    add(bundle, new ImagingStudy(), study, json -> {
      json.writeArrayFieldStart("identifier");
      json.writeStartObject();
      field(json, "use", "official");
      field(json, "system", "urn:ietf:rfc:3986");
      field(json, "value", "urn:oid:" + study.dicomUid);
      json.writeEndObject();
      json.writeEndArray();
      field(json, "status", "available");
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      dateTime(json, "started", study.start);
      location(json, encounter);
      json.writeNumberField("numberOfSeries", study.series.size());
      json.writeNumberField("numberOfInstances", study.series.stream()
          .mapToInt(series -> series.instances.size()).sum());
      if (!study.codes.isEmpty()) {
        json.writeArrayFieldStart("procedureCode");
        conceptItem(json, study.codes.get(0), SNOMED);
        json.writeEndArray();
      }
      if (!study.series.isEmpty()) {
        json.writeArrayFieldStart("series");
        int seriesNumber = 1;
        for (HealthRecord.ImagingStudy.Series series : study.series) {
          json.writeStartObject();
          field(json, "uid", series.dicomUid);
          json.writeNumberField("number", seriesNumber++);
          json.writeFieldName("modality");
          coding(json, series.modality, "http://dicom.nema.org/resources/ontology/DCM");
          json.writeFieldName("bodySite");
          coding(json, series.bodySite, SNOMED);
          dateTime(json, "started", study.start);
          json.writeNumberField("numberOfInstances", series.instances.size());
          if (!series.instances.isEmpty()) {
            json.writeArrayFieldStart("instance");
            int instanceNumber = 1;
            for (HealthRecord.ImagingStudy.Instance instance : series.instances) {
              json.writeStartObject();
              field(json, "uid", instance.dicomUid);
              json.writeFieldName("sopClass");
              rawCoding(json, "urn:ietf:rfc:3986", "urn:oid:" + instance.sopClass.code, null, null);
              json.writeNumberField("number", instanceNumber++);
              field(json, "title", instance.title);
              json.writeEndObject();
            }
            json.writeEndArray();
          }
          json.writeEndObject();
        }
        json.writeEndArray();
      }
    });
  }

  private static void location(JsonGenerator json, BundleEntryComponent encounter)
      throws IOException {
    if (FhirR4.USE_US_CORE_IG) {
      Reference location = ((org.hl7.fhir.r4.model.Encounter) encounter.getResource())
          .getLocationFirstRep().getLocation();
      reference(json, "location", location.getReference(), location.getDisplay());
    }
  }

  void observation(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      Observation observation) {
    add(bundle, new org.hl7.fhir.r4.model.Observation(), observation, json -> {
      List<String> profiles = FhirR4.observationProfiles(observation);
      if (!profiles.isEmpty()) {
        json.writeObjectFieldStart("meta");
        json.writeArrayFieldStart("profile");
        for (String profile : profiles) {
          json.writeString(profile);
        }
        json.writeEndArray();
        json.writeEndObject();
      }
      field(json, "status", "final");
      json.writeArrayFieldStart("category");
      category(json, observation.category,
          "http://terminology.hl7.org/CodeSystem/observation-category",
          observation.category == null ? null
              : StringUtils.capitalize(observation.category.replace('-', ' ')));
      if (FhirR4.USE_US_CORE_IG && FhirR4.useUSCore5()
          && "exam".equals(observation.category)) {
        category(json, "clinical-test", "http://hl7.org/fhir/us/core/CodeSystem/us-core-observation-category",
            "Clinical Test");
      }
      json.writeEndArray();
      json.writeObjectFieldStart("code");
      field(json, "text", observation.codes.get(0).display);
      json.writeArrayFieldStart("coding");
      coding(json, observation.codes.get(0), LOINC);
      for (int i = 1; i < observation.codes.size(); i++) {
        Code code = observation.codes.get(i);
        rawCoding(json, LOINC, code.code, code.display, null);
      }
      json.writeEndArray();
      json.writeEndObject();
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      dateTime(json, "effectiveDateTime", observation.start);
      field(json, "issued", new InstantType(new Date(observation.start)).getValueAsString());
      if (observation.value != null) {
        value(json, observation.value, observation.unit);
      } else if (observation.observations != null && !observation.observations.isEmpty()) {
        json.writeArrayFieldStart("component");
        for (Observation component : observation.observations) {
          json.writeStartObject();
          concept(json, "code", component.codes.get(0), LOINC);
          value(json, component.value, component.unit);
          json.writeEndObject();
        }
        json.writeEndArray();
      }
    });
  }

  void allergy(Bundle bundle, BundleEntryComponent patient, HealthRecord.Allergy allergy) {
    add(bundle, new AllergyIntolerance(), allergy, json -> {
      profile(json, "us-core-allergyintolerance");
      json.writeObjectFieldStart("clinicalStatus");
      categoryContents(json, allergy.stop == 0 ? "active" : "inactive",
          "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", null);
      json.writeEndObject();
      json.writeObjectFieldStart("verificationStatus");
      categoryContents(json, "confirmed",
          "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification", null);
      json.writeEndObject();
      field(json, "type", allergy.allergyType == null
          || "allergy".equalsIgnoreCase(allergy.allergyType) ? "allergy" : "intolerance");
      if (allergy.category != null) {
        String category = allergy.category;
        if (!List.of("food", "medication", "environment", "biologic").contains(category)) {
          category = "medication";
        }
        json.writeArrayFieldStart("category");
        json.writeString(category);
        json.writeEndArray();
      }
      field(json, "criticality", "low");
      concept(json, "code", allergy.codes.get(0), SNOMED);
      reference(json, "patient", patient.getFullUrl(), null);
      dateTime(json, "recordedDate", allergy.start);
      if (allergy.reactions != null && !allergy.reactions.isEmpty()) {
        List<Code> reactions = new ArrayList<>(allergy.reactions.keySet());
        reactions.sort(Comparator.comparing(code -> code.code));
        json.writeArrayFieldStart("reaction");
        for (Code reaction : reactions) {
          json.writeStartObject();
          json.writeArrayFieldStart("manifestation");
          conceptItem(json, reaction, SNOMED);
          json.writeEndArray();
          HealthRecord.ReactionSeverity severity = allergy.reactions.get(reaction);
          if (severity != null) {
            field(json, "severity", severity.name().toLowerCase(Locale.ROOT));
          }
          json.writeEndObject();
        }
        json.writeEndArray();
      }
    });
  }

  void immunization(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      HealthRecord.Entry immunization) {
    add(bundle, new Immunization(), immunization, json -> {
      profile(json, "us-core-immunization");
      field(json, "status", "completed");
      concept(json, "vaccineCode", immunization.codes.get(0), "http://hl7.org/fhir/sid/cvx");
      reference(json, "patient", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      dateTime(json, "occurrenceDateTime", immunization.start);
      json.writeBooleanField("primarySource", true);
      if (FhirR4.USE_US_CORE_IG) {
        org.hl7.fhir.r4.model.Encounter resource =
            (org.hl7.fhir.r4.model.Encounter) encounter.getResource();
        Reference location = resource.getLocationFirstRep().getLocation();
        reference(json, "location", location.getReference(), location.getDisplay());
      }
    });
  }

  void device(Bundle bundle, BundleEntryComponent patient, HealthRecord.Device device) {
    add(bundle, new Device(), device, json -> {
      profile(json, "us-core-implantable-device");
      if (present(device.deviceIdentifier) || present(device.udi)) {
        json.writeArrayFieldStart("udiCarrier");
        json.writeStartObject();
        field(json, "deviceIdentifier", device.deviceIdentifier);
        field(json, "carrierHRF", device.udi);
        json.writeEndObject();
        json.writeEndArray();
      }
      field(json, "status", "active");
      field(json, "distinctIdentifier", device.deviceIdentifier);
      field(json, "manufacturer", device.manufacturer);
      dateTime(json, "manufactureDate", device.manufactureTime);
      dateTime(json, "expirationDate", device.expirationTime);
      field(json, "lotNumber", device.lotNumber);
      field(json, "serialNumber", device.serialNumber);
      json.writeArrayFieldStart("deviceName");
      json.writeStartObject();
      field(json, "name", device.codes.get(0).display);
      field(json, "type", "user-friendly-name");
      json.writeEndObject();
      json.writeEndArray();
      field(json, "modelNumber", device.model);
      concept(json, "type", device.codes.get(0), SNOMED);
      reference(json, "patient", patient.getFullUrl(), null);
    });
  }

  void supply(Bundle bundle, BundleEntryComponent patient, HealthRecord.Supply supply) {
    add(bundle, new SupplyDelivery(), supply, json -> {
      field(json, "status", "completed");
      reference(json, "patient", patient.getFullUrl(), null);
      json.writeObjectFieldStart("type");
      categoryContents(json, "device",
          "http://terminology.hl7.org/CodeSystem/supply-item-type", "Device");
      json.writeEndObject();
      json.writeObjectFieldStart("suppliedItem");
      json.writeObjectFieldStart("quantity");
      json.writeNumberField("value", supply.quantity);
      json.writeEndObject();
      concept(json, "itemCodeableConcept", supply.codes.get(0), SNOMED);
      json.writeEndObject();
      dateTime(json, "occurrenceDateTime", supply.start);
    });
  }

  void media(Bundle bundle, BundleEntryComponent patient, BundleEntryComponent encounter,
      Observation observation) {
    add(bundle, new Media(), observation, json -> {
      field(json, "status", "completed");
      concept(json, "type", new Code("http://terminology.hl7.org/CodeSystem/media-type",
          "image", "Image"), null);
      reference(json, "subject", patient.getFullUrl(), null);
      reference(json, "encounter", encounter.getFullUrl(), null);
      if (observation.codes != null && !observation.codes.isEmpty()) {
        json.writeArrayFieldStart("reasonCode");
        for (Code code : observation.codes) {
          conceptItem(json, code, SNOMED);
        }
        json.writeEndArray();
      }
      Attachment content = (Attachment) observation.value;
      json.writeNumberField("height", content.height);
      json.writeNumberField("width", content.width);
      json.writeObjectFieldStart("content");
      field(json, "contentType", content.contentType);
      field(json, "language", content.language);
      field(json, "data", content.data == null ? null
          : new Base64BinaryType(content.data).getValueAsString());
      field(json, "url", content.url);
      if (content.data == null) {
        json.writeNumberField("size", content.size);
      }
      field(json, "hash", content.hash == null ? null
          : new Base64BinaryType(content.hash).getValueAsString());
      field(json, "title", content.title);
      json.writeEndObject();
    });
  }

  private void add(Bundle bundle, Resource shell, HealthRecord.Entry source,
      ResourceWriter writer) {
    BundleEntryComponent entry = FhirR4.newEntry(bundle, shell, source.uuid.toString());
    // Exactly the original mapper's fullUrl side effects: other exporters and claims use these.
    if (shell instanceof org.hl7.fhir.r4.model.Observation
        || shell instanceof AllergyIntolerance || shell instanceof Immunization
        || shell instanceof Condition || shell instanceof Procedure) {
      source.fullUrl = entry.getFullUrl();
    }
    resources.put(shell, writer);
  }

  void write(Bundle bundle, Writer output, boolean pretty) throws IOException {
    IParser fallback = FhirR4.getContext().newJsonParser().setPrettyPrint(pretty);
    JsonGenerator json = JSON.createGenerator(output);
    json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    if (pretty) {
      json.useDefaultPrettyPrinter();
    }
    try (JsonGenerator owned = json) {
      json.writeStartObject();
      field(json, "resourceType", "Bundle");
      field(json, "type", bundle.getType().toCode());
      if (bundle.hasEntry()) {
        json.writeArrayFieldStart("entry");
        for (BundleEntryComponent entry : bundle.getEntry()) {
          json.writeStartObject();
          field(json, "fullUrl", entry.getFullUrl());
          json.writeFieldName("resource");
          Resource resource = entry.getResource();
          ResourceWriter direct = resources.get(resource);
          if (direct == null) {
            json.writeRawValue(fallback.encodeResourceToString(resource));
          } else {
            json.writeStartObject();
            field(json, "resourceType", resource.fhirType());
            field(json, "id", resource.getIdElement().getIdPart());
            direct.write(json);
            json.writeEndObject();
          }
          if (entry.hasRequest()) {
            json.writeObjectFieldStart("request");
            field(json, "method", entry.getRequest().getMethod().toCode());
            field(json, "url", entry.getRequest().getUrl());
            field(json, "ifNoneExist", entry.getRequest().getIfNoneExist());
            json.writeEndObject();
          }
          json.writeEndObject();
        }
        json.writeEndArray();
      }
      json.writeEndObject();
    }
  }

  private static void value(JsonGenerator json, Object value, String unit) throws IOException {
    if (value == null) {
      return;
    }
    if (value instanceof Code) {
      concept(json, "valueCodeableConcept", (Code) value, SNOMED);
    } else if (value instanceof String) {
      field(json, "valueString", (String) value);
    } else if (value instanceof Number) {
      quantity(json, "valueQuantity", new PlainBigDecimal(((Number) value).doubleValue()), unit);
    } else if (value instanceof Boolean) {
      json.writeBooleanField("valueBoolean", (Boolean) value);
    } else if (value instanceof Components.SampledData) {
      Components.SampledData data = (Components.SampledData) value;
      json.writeObjectFieldStart("valueSampledData");
      quantity(json, "origin", BigDecimal.valueOf(data.originValue), unit);
      json.writeNumberField("period", data.series.get(0).getPeriod() * 1000);
      decimal(json, "factor", data.factor);
      decimal(json, "lowerLimit", data.lowerLimit);
      decimal(json, "upperLimit", data.upperLimit);
      json.writeNumberField("dimensions", data.series.size());
      field(json, "data", ExportHelper.sampledDataToValueString(data));
      json.writeEndObject();
    } else {
      throw new IllegalArgumentException("unexpected observation value class: "
          + value.getClass().toString() + "; " + value);
    }
  }

  private static void decimal(JsonGenerator json, String name, Double value) throws IOException {
    if (value != null) {
      json.writeNumberField(name, BigDecimal.valueOf(value));
    }
  }

  private static void quantity(JsonGenerator json, String name, BigDecimal value, String unit)
      throws IOException {
    json.writeObjectFieldStart(name);
    json.writeFieldName("value");
    json.writeNumber(value.toString());
    field(json, "unit", unit);
    field(json, "system", UCUM);
    field(json, "code", unit);
    json.writeEndObject();
  }

  private static void profile(JsonGenerator json, String name) throws IOException {
    if (FhirR4.USE_US_CORE_IG) {
      json.writeObjectFieldStart("meta");
      json.writeArrayFieldStart("profile");
      json.writeString(CORE + name);
      json.writeEndArray();
      json.writeEndObject();
    }
  }

  private static void concept(JsonGenerator json, String name, Code code, String fallback)
      throws IOException {
    json.writeFieldName(name);
    conceptItem(json, code, fallback);
  }

  private static void conceptItem(JsonGenerator json, Code code, String fallback)
      throws IOException {
    json.writeStartObject();
    json.writeArrayFieldStart("coding");
    coding(json, code, fallback);
    json.writeEndArray();
    field(json, "text", code.display);
    json.writeEndObject();
  }

  private static void conceptItem(JsonGenerator json, CodeableConcept concept) throws IOException {
    json.writeStartObject();
    if (concept.hasCoding()) {
      json.writeArrayFieldStart("coding");
      for (Coding coding : concept.getCoding()) {
        rawCoding(json, coding.getSystem(), coding.getCode(),
            coding.getDisplay(), coding.getVersion());
      }
      json.writeEndArray();
    }
    field(json, "text", concept.getText());
    json.writeEndObject();
  }

  private static void coding(JsonGenerator json, Code code, String fallback) throws IOException {
    String system = ExportHelper.getSystemURI(code.system);
    rawCoding(json, system == null ? ExportHelper.getSystemURI(fallback) : system,
        code.code, code.display, code.version);
  }

  private static void rawCoding(JsonGenerator json, String system, String code, String display,
      String version) throws IOException {
    json.writeStartObject();
    field(json, "system", system);
    field(json, "version", version);
    field(json, "code", code);
    field(json, "display", display);
    json.writeEndObject();
  }

  private static void category(JsonGenerator json, String code, String system, String display)
      throws IOException {
    json.writeStartObject();
    categoryContents(json, code, system, display);
    json.writeEndObject();
  }

  private static void categoryContents(JsonGenerator json, String code, String system,
      String display) throws IOException {
    json.writeArrayFieldStart("coding");
    rawCoding(json, system, code, display, null);
    json.writeEndArray();
  }

  private static void reference(JsonGenerator json, String name, String reference, String display)
      throws IOException {
    if (present(reference) || present(display)) {
      json.writeObjectFieldStart(name);
      field(json, "reference", reference);
      field(json, "display", display);
      json.writeEndObject();
    }
  }

  private static void referenceItem(JsonGenerator json, String reference, String display)
      throws IOException {
    json.writeStartObject();
    field(json, "reference", reference);
    field(json, "display", display);
    json.writeEndObject();
  }

  private static void dateTime(JsonGenerator json, String name, long time) throws IOException {
    field(json, name, new DateTimeType(new Date(time)).getValueAsString());
  }

  private static boolean present(String value) {
    return value != null && !value.isEmpty();
  }

  private static void field(JsonGenerator json, String name, String value) throws IOException {
    if (present(value)) {
      json.writeStringField(name, value);
    }
  }
}
