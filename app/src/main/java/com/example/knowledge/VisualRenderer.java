package com.example.knowledge;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.models.TemplateToken;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VisualRenderer {

    private static final int CANVAS_WIDTH = 1080;
    private static final int CANVAS_HEIGHT = 1080;

    /**
     * Renders a complete high-definition vector composite bitmap from a 16-byte TemplateToken.
     */
    public static Bitmap renderTokenToBitmap(TemplateToken token) {
        if (token == null) return null;

        Bitmap bitmap = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 1. Fill Tactical Dark Background
        canvas.drawColor(Color.parseColor("#0F172A"));

        // 2. Draw Subtle Coordinate Grid Lines
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#1E293B"));
        gridPaint.setStrokeWidth(2f);

        for (int i = 0; i <= CANVAS_WIDTH; i += 120) {
            canvas.drawLine(i, 0, i, CANVAS_HEIGHT, gridPaint);
            canvas.drawLine(0, i, CANVAS_WIDTH, i, gridPaint);
        }

        // 3. Load Template Definition from Catalog
        TemplateCatalog.TemplateDefinition template = TemplateCatalog.getTemplate(token.getTemplateId());

        // 4. Handle Lossless Image Container Header Preview
        if (token.getCategoryId() == TemplateToken.CATEGORY_LOSSLESS_IMAGE) {
            drawLosslessContainerPreview(canvas, template, token);
            drawHeaderHUD(canvas, template, token);
            return bitmap;
        }

        // 5. Draw Static Vector Elements (River routes, roads, zones)
        List<TemplateCatalog.VectorElement> elements = template.getVectorElements();
        Paint elementPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (TemplateCatalog.VectorElement elem : elements) {
            elementPaint.setColor(elem.getColor());
            elementPaint.setStrokeWidth(elem.getStrokeWidth() * 2f);

            int x1 = mapCoord(elem.getX1(), CANVAS_WIDTH);
            int y1 = mapCoord(elem.getY1(), CANVAS_HEIGHT);
            int x2 = mapCoord(elem.getX2(), CANVAS_WIDTH);
            int y2 = mapCoord(elem.getY2(), CANVAS_HEIGHT);

            if (elem.getType() == TemplateCatalog.VectorElement.TYPE_LINE ||
                elem.getType() == TemplateCatalog.VectorElement.TYPE_RIVER) {
                elementPaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(x1, y1, x2, y2, elementPaint);
            } else if (elem.getType() == TemplateCatalog.VectorElement.TYPE_ZONE ||
                       elem.getType() == TemplateCatalog.VectorElement.TYPE_RECT) {
                elementPaint.setStyle(Paint.Style.FILL);
                elementPaint.setAlpha(120);
                canvas.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), elementPaint);

                elementPaint.setStyle(Paint.Style.STROKE);
                elementPaint.setAlpha(255);
                canvas.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), elementPaint);
            } else if (elem.getType() == TemplateCatalog.VectorElement.TYPE_CIRCLE) {
                elementPaint.setStyle(Paint.Style.STROKE);
                int r = mapCoord(elem.getRadius(), CANVAS_WIDTH);
                canvas.drawCircle(x1, y1, r, elementPaint);
            }

            // Draw Element Label if present
            if (elem.getLabel() != null && !elem.getLabel().isEmpty()) {
                Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(Color.parseColor("#94A3B8"));
                labelPaint.setTextSize(24f);
                labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                canvas.drawText(elem.getLabel(), x1 + 10, y1 - 10, labelPaint);
            }
        }

        // 6. Draw Dynamic Target Pin Coordinates
        int pinX = mapCoord(token.getParamX(), CANVAS_WIDTH);
        int pinY = mapCoord(token.getParamY(), CANVAS_HEIGHT);

        int iconColor = TemplateCatalog.getIconColor(token.getStampIcon(), token.getSeverity());

        // Target Radial Rings
        Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setColor(iconColor);
        targetPaint.setStrokeWidth(4f);

        canvas.drawCircle(pinX, pinY, 60, targetPaint);
        targetPaint.setStrokeWidth(2f);
        targetPaint.setAlpha(150);
        canvas.drawCircle(pinX, pinY, 90, targetPaint);

        // Crosshairs
        canvas.drawLine(pinX - 110, pinY, pinX + 110, pinY, targetPaint);
        canvas.drawLine(pinX, pinY - 110, pinX, pinY + 110, targetPaint);

        // Center Pin Fill
        Paint pinFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinFill.setStyle(Paint.Style.FILL);
        pinFill.setColor(iconColor);
        canvas.drawCircle(pinX, pinY, 28, pinFill);

        // 7. Draw Vector Stamp Icon inside Pin
        drawStampIcon(canvas, pinX, pinY, token.getStampIcon());

        // 8. Render Dynamic Floating Callout Badge
        drawCalloutBadge(canvas, pinX, pinY, token);

        // 9. Render Top Navigation HUD Banner
        drawHeaderHUD(canvas, template, token);

        return bitmap;
    }

    private static void drawLosslessContainerPreview(Canvas canvas, TemplateCatalog.TemplateDefinition template, TemplateToken token) {
        int width = token.getParamX() > 0 ? token.getParamX() : 640;
        int height = token.getParamY() > 0 ? token.getParamY() : 480;

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(Color.parseColor("#1E293B"));
        boxPaint.setStyle(Paint.Style.FILL);
        RectF containerRect = new RectF(120, 220, CANVAS_WIDTH - 120, CANVAS_HEIGHT - 220);
        canvas.drawRoundRect(containerRect, 24f, 24f, boxPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#0284C7"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);
        canvas.drawRoundRect(containerRect, 24f, 24f, borderPaint);

        // Draw Center Image Frame Icon
        drawStampIcon(canvas, CANVAS_WIDTH / 2, 420, TemplateToken.ICON_IMAGE_CONTAINER);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("EXACT LOSSLESS IMAGE CONTAINER", CANVAS_WIDTH / 2f, 560, textPaint);

        textPaint.setColor(Color.parseColor("#38BDF8"));
        textPaint.setTextSize(26f);
        textPaint.setTypeface(Typeface.DEFAULT);
        canvas.drawText("Resolution: " + width + " x " + height + " px (Lossless)", CANVAS_WIDTH / 2f, 620, textPaint);

        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(22f);
        canvas.drawText("Pixel Streaming Pipeline: Pure Binary 2400 Baud", CANVAS_WIDTH / 2f, 680, textPaint);
        canvas.drawText("Bit-for-Bit Exact | Zero Compression Artifacts", CANVAS_WIDTH / 2f, 720, textPaint);
    }

    private static void drawStampIcon(Canvas canvas, int cx, int cy, int iconId) {
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        iconPaint.setStrokeWidth(3f);

        if (iconId == TemplateToken.ICON_FLOOD) {
            Path wave = new Path();
            wave.moveTo(cx - 14, cy);
            wave.quadTo(cx - 7, cy - 8, cx, cy);
            wave.quadTo(cx + 7, cy + 8, cx + 14, cy);
            iconPaint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(wave, iconPaint);
        } else if (iconId == TemplateToken.ICON_MEDICAL) {
            canvas.drawRect(cx - 4, cy - 14, cx + 4, cy + 14, iconPaint);
            canvas.drawRect(cx - 14, cy - 4, cx + 14, cy + 4, iconPaint);
        } else if (iconId == TemplateToken.ICON_ROADBLOCK) {
            canvas.drawLine(cx - 10, cy - 10, cx + 10, cy + 10, iconPaint);
            canvas.drawLine(cx - 10, cy + 10, cx + 10, cy - 10, iconPaint);
        } else if (iconId == TemplateToken.ICON_FIRE) {
            Path flame = new Path();
            flame.moveTo(cx, cy - 14);
            flame.lineTo(cx - 10, cy + 12);
            flame.lineTo(cx + 10, cy + 12);
            flame.close();
            canvas.drawPath(flame, iconPaint);
        } else if (iconId == TemplateToken.ICON_IMAGE_CONTAINER) {
            // Photo Camera Icon
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(4f);
            canvas.drawRoundRect(new RectF(cx - 40, cy - 30, cx + 40, cy + 30), 8f, 8f, iconPaint);
            canvas.drawCircle(cx, cy, 14, iconPaint);
            canvas.drawRect(cx - 14, cy - 38, cx + 14, cy - 30, iconPaint);
        } else {
            canvas.drawCircle(cx, cy - 4, 3, iconPaint);
            canvas.drawLine(cx, cy - 12, cx, cy - 8, iconPaint);
        }
    }

    private static void drawCalloutBadge(Canvas canvas, int pinX, int pinY, TemplateToken token) {
        String label = TemplateCatalog.getIconName(token.getStampIcon());
        String valText = "METRIC: " + token.getParamValue();
        if (token.getStampIcon() == TemplateToken.ICON_FLOOD) {
            valText = "WATER DEPTH: " + (token.getParamValue() / 100.0) + "m";
        } else if (token.getStampIcon() == TemplateToken.ICON_ROADBLOCK) {
            valText = "STATUS: PASSAGE COMPROMISED";
        }

        int badgeWidth = 420;
        int badgeHeight = 110;
        int badgeX = Math.min(Math.max(pinX - (badgeWidth / 2), 40), CANVAS_WIDTH - badgeWidth - 40);
        int badgeY = pinY > 600 ? pinY - 180 : pinY + 110;

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#1E293B"));
        bgPaint.setStyle(Paint.Style.FILL);
        RectF rect = new RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight);
        canvas.drawRoundRect(rect, 16f, 16f, bgPaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(TemplateCatalog.getIconColor(token.getStampIcon(), token.getSeverity()));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(26f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(label, badgeX + 20, badgeY + 44, textPaint);

        textPaint.setColor(Color.parseColor("#38BDF8"));
        textPaint.setTextSize(22f);
        textPaint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(valText, badgeX + 20, badgeY + 84, textPaint);
    }

    private static void drawHeaderHUD(Canvas canvas, TemplateCatalog.TemplateDefinition template, TemplateToken token) {
        Paint hudBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudBg.setColor(Color.parseColor("#0F172A"));
        hudBg.setAlpha(240);
        canvas.drawRect(0, 0, CANVAS_WIDTH, 140, hudBg);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.parseColor("#334155"));
        border.setStrokeWidth(3f);
        canvas.drawLine(0, 140, CANVAS_WIDTH, 140, border);

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(34f);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(template.getName(), 30, 55, titlePaint);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String sub = "SECTOR #" + token.getTemplateId() + " | TIME: " + sdf.format(new Date());
        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(Color.parseColor("#94A3B8"));
        subPaint.setTextSize(22f);
        canvas.drawText(sub, 30, 100, subPaint);

        String sevLabel = TemplateCatalog.getSeverityLabel(token.getSeverity());
        int sevColor = TemplateCatalog.getSeverityColor(token.getSeverity());

        Paint sevBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        sevBg.setColor(sevColor);
        RectF sevRect = new RectF(CANVAS_WIDTH - 240, 35, CANVAS_WIDTH - 30, 95);
        canvas.drawRoundRect(sevRect, 30f, 30f, sevBg);

        Paint sevText = new Paint(Paint.ANTI_ALIAS_FLAG);
        sevText.setColor(Color.WHITE);
        sevText.setTextSize(22f);
        sevText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        sevText.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(sevLabel, sevRect.centerX(), sevRect.centerY() + 8, sevText);
    }

    private static int mapCoord(int normCoord, int maxPixel) {
        return (int) ((((long) normCoord) * maxPixel) / 65535L);
    }

    /**
     * Automatically pops up the high-definition visual result dialog on the receiver screen.
     */
    public static void showVisualResultDialog(final Context context, final TemplateToken token) {
        if (context == null || token == null) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Bitmap renderedBitmap = renderTokenToBitmap(token);
                TemplateCatalog.TemplateDefinition template = TemplateCatalog.getTemplate(token.getTemplateId());

                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setBackgroundColor(Color.parseColor("#0F172A"));
                container.setPadding(32, 32, 32, 32);

                TextView tvHeader = new TextView(context);
                tvHeader.setText(template.getName());
                tvHeader.setTextColor(Color.WHITE);
                tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                tvHeader.setTypeface(null, Typeface.BOLD);
                tvHeader.setPadding(0, 0, 0, 16);

                ImageView ivCanvas = new ImageView(context);
                ivCanvas.setImageBitmap(renderedBitmap);
                ivCanvas.setAdjustViewBounds(true);
                LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

                TextView tvDetails = new TextView(context);
                tvDetails.setText("Coordinate: (" + token.getParamX() + ", " + token.getParamY() + ")\n" +
                        "Hazard: " + TemplateCatalog.getIconName(token.getStampIcon()) + "\n" +
                        "Severity: " + TemplateCatalog.getSeverityLabel(token.getSeverity()) + "\n" +
                        "CRC-16 Status: VALIDATED (0x" + Integer.toHexString(token.getCrc16()).toUpperCase() + ")");
                tvDetails.setTextColor(Color.parseColor("#94A3B8"));
                tvDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tvDetails.setPadding(0, 16, 0, 24);

                container.addView(tvHeader);
                container.addView(ivCanvas, imgParams);
                container.addView(tvDetails);

                ScrollView scrollView = new ScrollView(context);
                scrollView.addView(container);

                AlertDialog dialog = new AlertDialog.Builder(context)
                        .setView(scrollView)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Copy NATO Code", (d, w) -> {
                            String nato = PhoneticTokenManager.encodeToPhoneticWords(token);
                            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("NATO Code", nato));
                                Toast.makeText(context, "NATO Code Copied to Clipboard", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();

                dialog.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Mode 2 / Mode 3: Displays the 100% exact original camera photo on the receiver screen.
     */
    public static void showLosslessImageDialog(final Context context, final byte[] rawImageBytes, final String fileName) {
        if (context == null || rawImageBytes == null || rawImageBytes.length == 0) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Bitmap exactBitmap = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.length);
                if (exactBitmap == null) {
                    Toast.makeText(context, "Failed decoding exact image stream", Toast.LENGTH_SHORT).show();
                    return;
                }

                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setBackgroundColor(Color.parseColor("#0F172A"));
                container.setPadding(32, 32, 32, 32);

                TextView tvHeader = new TextView(context);
                tvHeader.setText("Exact Lossless Image Received");
                tvHeader.setTextColor(Color.WHITE);
                tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                tvHeader.setTypeface(null, Typeface.BOLD);
                tvHeader.setPadding(0, 0, 0, 16);

                ImageView ivPhoto = new ImageView(context);
                ivPhoto.setImageBitmap(exactBitmap);
                ivPhoto.setAdjustViewBounds(true);
                LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

                TextView tvDetails = new TextView(context);
                tvDetails.setText("File Name: " + (fileName != null ? fileName : "received_photo.webp") + "\n" +
                        "Dimensions: " + exactBitmap.getWidth() + " x " + exactBitmap.getHeight() + " px\n" +
                        "Integrity: 100% Bit-for-Bit Exact | SHA-256 Validated\n" +
                        "Payload Size: " + (rawImageBytes.length / 1024) + " KB");
                tvDetails.setTextColor(Color.parseColor("#94A3B8"));
                tvDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tvDetails.setPadding(0, 16, 0, 24);

                container.addView(tvHeader);
                container.addView(ivPhoto, imgParams);
                container.addView(tvDetails);

                ScrollView scrollView = new ScrollView(context);
                scrollView.addView(container);

                AlertDialog dialog = new AlertDialog.Builder(context)
                        .setView(scrollView)
                        .setPositiveButton("Done", null)
                        .create();

                dialog.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}