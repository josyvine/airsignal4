package com.example.knowledge;

import android.graphics.Color;

import com.example.models.TemplateToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TemplateCatalog {

    public static final int TEMPLATE_CHALAKUDY_SECTOR_MAP = 1;
    public static final int TEMPLATE_MEDICAL_TRIAGE_REPORT = 2;
    public static final int TEMPLATE_ROADBLOCK_INFRASTRUCTURE = 3;
    public static final int TEMPLATE_LOGISTICS_SUPPLY_GRID = 4;
    public static final int TEMPLATE_SEARCH_RESCUE_STATUS = 5;
    public static final int TEMPLATE_IMAGE_WEBP_LOSSLESS = 6;
    public static final int TEMPLATE_IMAGE_PNG_LOSSLESS = 7;

    private static final Map<Integer, TemplateDefinition> CATALOG = new HashMap<>();

    static {
        initializeCatalog();
    }

    private static void initializeCatalog() {
        // -------------------------------------------------------------
        // Template 1: Chalakudy / Kerala Disaster Sector Grid Map
        // -------------------------------------------------------------
        TemplateDefinition mapTemplate = new TemplateDefinition(
                TEMPLATE_CHALAKUDY_SECTOR_MAP,
                TemplateToken.CATEGORY_TACTICAL_MAP,
                "Chalakudy River & Bridge Sector Grid",
                "High-resolution tactical vector map covering river basins, bridges, and NH-544 highway corridor."
        );
        // Base river route vector path
        mapTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_RIVER, 0, 32000, 65535, 34000, 0, Color.parseColor("#0284C7"), 8f, "Chalakudy River"));
        // Highway line
        mapTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_LINE, 20000, 0, 22000, 65535, 0, Color.parseColor("#F59E0B"), 6f, "NH-544 Highway"));
        // Main Bridge marker zone
        mapTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_ZONE, 18000, 30000, 24000, 36000, 0, Color.parseColor("#334155"), 2f, "Main River Bridge Sector"));
        // Hospital zone
        mapTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_ZONE, 42000, 15000, 52000, 25000, 0, Color.parseColor("#1E293B"), 2f, "Govt Hospital Zone"));
        // Relief Camp zone
        mapTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_ZONE, 45000, 45000, 58000, 58000, 0, Color.parseColor("#1E293B"), 2f, "St. Marys Relief Shelter"));
        CATALOG.put(TEMPLATE_CHALAKUDY_SECTOR_MAP, mapTemplate);

        // -------------------------------------------------------------
        // Template 2: Emergency Medical Triage Report
        // -------------------------------------------------------------
        TemplateDefinition triageTemplate = new TemplateDefinition(
                TEMPLATE_MEDICAL_TRIAGE_REPORT,
                TemplateToken.CATEGORY_MEDICAL_TRIAGE,
                "Emergency Medical Triage Form",
                "Standardized START disaster triage protocol and rapid vital assessment."
        );
        triageTemplate.addFormField(new FormField("patient_id", "Patient Identification / Unit", FormField.TYPE_TEXT, "P-0000", ""));
        triageTemplate.addFormField(new FormField("respiration_rate", "Respiration Rate", FormField.TYPE_NUMBER, "0", "BPM"));
        triageTemplate.addFormField(new FormField("pulse_rate", "Radial Pulse / HR", FormField.TYPE_NUMBER, "0", "BPM"));
        triageTemplate.addFormField(new FormField("mental_status", "Responsiveness / GCS", FormField.TYPE_TEXT, "Alert", ""));
        triageTemplate.addFormField(new FormField("triage_color", "Triage Tag Priority", FormField.TYPE_COLOR_TAG, "RED", ""));
        CATALOG.put(TEMPLATE_MEDICAL_TRIAGE_REPORT, triageTemplate);

        // -------------------------------------------------------------
        // Template 3: Roadblock & Infrastructure Hazard Assessment
        // -------------------------------------------------------------
        TemplateDefinition hazardTemplate = new TemplateDefinition(
                TEMPLATE_ROADBLOCK_INFRASTRUCTURE,
                TemplateToken.CATEGORY_DISASTER_HAZARD,
                "Infrastructure & Roadblock Assessment",
                "Bridge structural safety, water level gauge, and impassable debris locator."
        );
        hazardTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_LINE, 5000, 32768, 60535, 32768, 0, Color.parseColor("#64748B"), 10f, "Main Transit Arterial"));
        hazardTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_CIRCLE, 32768, 32768, 0, 0, 8000, Color.parseColor("#EF4444"), 3f, "Hazard Impact Perimeter"));
        hazardTemplate.addFormField(new FormField("water_depth", "Water Submersion Depth", FormField.TYPE_NUMBER, "0", "cm"));
        hazardTemplate.addFormField(new FormField("structural_integrity", "Bridge Structure", FormField.TYPE_TEXT, "COMPROMISED", ""));
        CATALOG.put(TEMPLATE_ROADBLOCK_INFRASTRUCTURE, hazardTemplate);

        // -------------------------------------------------------------
        // Template 4: Logistics Supply Drop Grid
        // -------------------------------------------------------------
        TemplateDefinition logisticsTemplate = new TemplateDefinition(
                TEMPLATE_LOGISTICS_SUPPLY_GRID,
                TemplateToken.CATEGORY_LOGISTICS,
                "Airdrop & Supply Depot Grid",
                "Coordinates for medical aid drops, clean water rationing, and boat rescue staging."
        );
        logisticsTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_ZONE, 10000, 10000, 30000, 30000, 0, Color.parseColor("#065F46"), 2f, "Drop Zone Alpha"));
        logisticsTemplate.addVectorElement(new VectorElement(VectorElement.TYPE_ZONE, 35000, 35000, 55000, 55000, 0, Color.parseColor("#1E3A8A"), 2f, "Water Rescue Depot"));
        logisticsTemplate.addFormField(new FormField("rations_remaining", "Available Food Packs", FormField.TYPE_NUMBER, "0", "Units"));
        logisticsTemplate.addFormField(new FormField("water_liters", "Potable Water Supply", FormField.TYPE_NUMBER, "0", "Liters"));
        CATALOG.put(TEMPLATE_LOGISTICS_SUPPLY_GRID, logisticsTemplate);

        // -------------------------------------------------------------
        // Template 5: Search & Rescue Team Status Card
        // -------------------------------------------------------------
        TemplateDefinition rescueTemplate = new TemplateDefinition(
                TEMPLATE_SEARCH_RESCUE_STATUS,
                TemplateToken.CATEGORY_TACTICAL_MAP,
                "Search & Rescue Field Unit Status",
                "Live tracking card for NDRF, Fire & Rescue, and volunteer boat squads."
        );
        rescueTemplate.addFormField(new FormField("team_callsign", "Unit Callsign", FormField.TYPE_TEXT, "RESCUE-01", ""));
        rescueTemplate.addFormField(new FormField("personnel_count", "Active Personnel", FormField.TYPE_NUMBER, "6", "Members"));
        rescueTemplate.addFormField(new FormField("boat_count", "Inflatable / Motor Boats", FormField.TYPE_NUMBER, "2", "Boats"));
        rescueTemplate.addFormField(new FormField("comms_channel", "VHF Radio Frequency", FormField.TYPE_TEXT, "156.800 MHz", ""));
        CATALOG.put(TEMPLATE_SEARCH_RESCUE_STATUS, rescueTemplate);

        // -------------------------------------------------------------
        // Template 6: Lossless WebP Photo Container (640x480)
        // -------------------------------------------------------------
        TemplateDefinition webpTemplate = new TemplateDefinition(
                TEMPLATE_IMAGE_WEBP_LOSSLESS,
                TemplateToken.CATEGORY_LOSSLESS_IMAGE,
                "Lossless WebP Photo Container",
                "Pre-built dictionary container for lossless WebP raw pixel streaming with zero quality loss."
        );
        webpTemplate.addFormField(new FormField("image_width", "Image Width", FormField.TYPE_NUMBER, "640", "px"));
        webpTemplate.addFormField(new FormField("image_height", "Image Height", FormField.TYPE_NUMBER, "480", "px"));
        webpTemplate.addFormField(new FormField("color_depth", "Color Space", FormField.TYPE_TEXT, "ARGB_8888", ""));
        CATALOG.put(TEMPLATE_IMAGE_WEBP_LOSSLESS, webpTemplate);

        // -------------------------------------------------------------
        // Template 7: Lossless PNG Graphic Container (320x240)
        // -------------------------------------------------------------
        TemplateDefinition pngTemplate = new TemplateDefinition(
                TEMPLATE_IMAGE_PNG_LOSSLESS,
                TemplateToken.CATEGORY_LOSSLESS_IMAGE,
                "Lossless PNG Graphic Container",
                "Pre-built dictionary container for lossless PNG thumbnails and diagram bitmaps."
        );
        pngTemplate.addFormField(new FormField("image_width", "Image Width", FormField.TYPE_NUMBER, "320", "px"));
        pngTemplate.addFormField(new FormField("image_height", "Image Height", FormField.TYPE_NUMBER, "240", "px"));
        pngTemplate.addFormField(new FormField("color_depth", "Color Space", FormField.TYPE_TEXT, "RGB_565", ""));
        CATALOG.put(TEMPLATE_IMAGE_PNG_LOSSLESS, pngTemplate);
    }

    public static TemplateDefinition getTemplate(int templateId) {
        if (CATALOG.containsKey(templateId)) {
            return CATALOG.get(templateId);
        }
        return CATALOG.get(TEMPLATE_CHALAKUDY_SECTOR_MAP);
    }

    public static List<TemplateDefinition> getAllTemplates() {
        return new ArrayList<>(CATALOG.values());
    }

    public static List<TemplateDefinition> getTemplatesByCategory(int categoryId) {
        List<TemplateDefinition> list = new ArrayList<>();
        for (TemplateDefinition def : CATALOG.values()) {
            if (def.getCategoryId() == categoryId) {
                list.add(def);
            }
        }
        return list;
    }

    public static String getIconName(int iconId) {
        switch (iconId) {
            case TemplateToken.ICON_FLOOD: return "Flood / Water Submersion";
            case TemplateToken.ICON_FIRE: return "Fire / Heat Hazard";
            case TemplateToken.ICON_ROADBLOCK: return "Road Blocked / Debris";
            case TemplateToken.ICON_MEDICAL: return "Medical Emergency / Casualty";
            case TemplateToken.ICON_SHELTER: return "Evacuation Shelter / Camp";
            case TemplateToken.ICON_HAZARD: return "Severe Electrical / Gas Hazard";
            case TemplateToken.ICON_IMAGE_CONTAINER: return "Exact Lossless Image Container";
            default: return "General Marker";
        }
    }

    public static int getIconColor(int iconId, int severity) {
        if (severity == TemplateToken.SEVERITY_CRITICAL) {
            return Color.parseColor("#EF4444"); // Bright Red
        }
        switch (iconId) {
            case TemplateToken.ICON_FLOOD: return Color.parseColor("#0284C7");
            case TemplateToken.ICON_FIRE: return Color.parseColor("#F97316");
            case TemplateToken.ICON_ROADBLOCK: return Color.parseColor("#EAB308");
            case TemplateToken.ICON_MEDICAL: return Color.parseColor("#DC2626");
            case TemplateToken.ICON_SHELTER: return Color.parseColor("#10B981");
            case TemplateToken.ICON_HAZARD: return Color.parseColor("#8B5CF6");
            case TemplateToken.ICON_IMAGE_CONTAINER: return Color.parseColor("#38BDF8");
            default: return Color.parseColor("#38BDF8");
        }
    }

    public static String getSeverityLabel(int severity) {
        switch (severity) {
            case TemplateToken.SEVERITY_CRITICAL: return "CRITICAL";
            case TemplateToken.SEVERITY_HIGH: return "HIGH PRIORITY";
            case TemplateToken.SEVERITY_MEDIUM: return "ELEVATED";
            default: return "ROUTINE / LOW";
        }
    }

    public static int getSeverityColor(int severity) {
        switch (severity) {
            case TemplateToken.SEVERITY_CRITICAL: return Color.parseColor("#EF4444");
            case TemplateToken.SEVERITY_HIGH: return Color.parseColor("#F97316");
            case TemplateToken.SEVERITY_MEDIUM: return Color.parseColor("#EAB308");
            default: return Color.parseColor("#10B981");
        }
    }

    // =========================================================================
    // Inner Models for Vector Primitives & Structured Forms
    // =========================================================================

    public static class TemplateDefinition {
        private final int id;
        private final int categoryId;
        private final String name;
        private final String description;
        private final List<VectorElement> vectorElements = new ArrayList<>();
        private final List<FormField> formFields = new ArrayList<>();

        public TemplateDefinition(int id, int categoryId, String name, String description) {
            this.id = id;
            this.categoryId = categoryId;
            this.name = name;
            this.description = description;
        }

        public void addVectorElement(VectorElement element) {
            if (element != null) this.vectorElements.add(element);
        }

        public void addFormField(FormField field) {
            if (field != null) this.formFields.add(field);
        }

        public int getId() { return id; }
        public int getCategoryId() { return categoryId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<VectorElement> getVectorElements() { return Collections.unmodifiableList(vectorElements); }
        public List<FormField> getFormFields() { return Collections.unmodifiableList(formFields); }
    }

    public static class VectorElement {
        public static final int TYPE_LINE = 1;
        public static final int TYPE_RECT = 2;
        public static final int TYPE_CIRCLE = 3;
        public static final int TYPE_RIVER = 4;
        public static final int TYPE_ZONE = 5;

        private final int type;
        private final int x1, y1, x2, y2;
        private final int radius;
        private final int color;
        private final float strokeWidth;
        private final String label;

        public VectorElement(int type, int x1, int y1, int x2, int y2, int radius, int color, float strokeWidth, String label) {
            this.type = type;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.radius = radius;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.label = label;
        }

        public int getType() { return type; }
        public int getX1() { return x1; }
        public int getY1() { return y1; }
        public int getX2() { return x2; }
        public int getY2() { return y2; }
        public int getRadius() { return radius; }
        public int getColor() { return color; }
        public float getStrokeWidth() { return strokeWidth; }
        public String getLabel() { return label; }
    }

    public static class FormField {
        public static final int TYPE_TEXT = 1;
        public static final int TYPE_NUMBER = 2;
        public static final int TYPE_COLOR_TAG = 3;

        private final String key;
        private final String label;
        private final int type;
        private final String defaultValue;
        private final String unit;

        public FormField(String key, String label, int type, String defaultValue, String unit) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.defaultValue = defaultValue;
            this.unit = unit;
        }

        public String getKey() { return key; }
        public String getLabel() { return label; }
        public int getType() { return type; }
        public String getDefaultValue() { return defaultValue; }
        public String getUnit() { return unit; }
    }
}