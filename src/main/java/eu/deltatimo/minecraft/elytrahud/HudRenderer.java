package eu.deltatimo.minecraft.elytrahud;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

public class HudRenderer {

    private static boolean hudEnabled = true;
    private static float hud_alpha = 0.0f;
    public static boolean shouldRenderHud(){
        return hudEnabled;
    }

    public static void switchHudEnabled(){
        hudEnabled = !hudEnabled;
    }

    public static void onHudRender(DrawContext context, float tickDelta) {

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        Window window = client.getWindow();

        double aspect = (double) window.getWidth() / (double) window.getHeight();

        double fov_deg = client.options.getFov().getValue();
        double fov = Math.toRadians(fov_deg);
        double hor_fov = 2.0 * Math.atan(Math.tan(fov / 2.0) * aspect);
        double hor_fov_deg = Math.toDegrees(hor_fov);

        int screenWidth = window.getScaledWidth();
        int screenHeight = window.getScaledHeight();
        int screenLesser = Math.min(screenWidth, screenHeight);
        int screenCenterX = screenWidth / 2;
        int screenCenterY = screenHeight / 2;

        Camera camera = client.gameRenderer.getCamera();
        double camera_pitch_deg = camera.getPitch();
        double camera_pitch = Math.toRadians(camera_pitch_deg);

        float pixels_per_deg = (float) (screenHeight / fov_deg);
        float pixels_per_hor_deg = (float) (screenHeight / hor_fov_deg);

        double screen_lower_pitch_deg = camera_pitch_deg + fov_deg / 2.0D;
        double screen_upper_pitch_deg = camera_pitch_deg - fov_deg / 2.0D;

        float horizon_width = screenWidth / 8f;
        float horizon_vertical_blip_length = screenHeight / 160f;
        float center_height = screenCenterY + ((float) (pixels_per_deg * -camera_pitch_deg));

        ClientPlayerEntity player = client.player;

        if (player != null) {
            /**
             * Figure out if the overlay needs to be rendered:
             * - the player is falling &&
             * - the hud is enabled
             * */
            float hud_alpha_target = player.isFallFlying() ? 1.0f : 0.0f;
            if (!hudEnabled) {
                hud_alpha_target = 0.0f;
            }

            // Fade in and out effect when overlay starts/stops rendering
            hud_alpha = Math.max(0f, Math.min(1f, hud_alpha + Math.signum(hud_alpha_target - hud_alpha) * tickDelta * 0.1f));

            // If the hud_alpha high enough, render the overlay
            if (hud_alpha >= 0.0001) {
                Vec3d player_pos = player.getPos();
                Vec3d velocity = player.getVelocity().rotateY((float) Math.toRadians(camera.getYaw())).rotateX((float) Math.toRadians(camera.getPitch()));

                double upwardSpeed = velocity.getY();
                double forwardSpeed = velocity.getZ();
                double sidewaysSpeed = -velocity.getX();

                double upwardSpeedAngle = Math.toDegrees(Math.atan2(upwardSpeed, forwardSpeed));
                double rightSpeedAngle = Math.toDegrees(Math.atan2(sidewaysSpeed, forwardSpeed));

                float flight_vector_x = screenCenterX + (float) (pixels_per_hor_deg * rightSpeedAngle);
                float flight_vector_y = screenCenterY - (float) (pixels_per_deg * upwardSpeedAngle);

                int radar_height = getRadarHeight(player, client.world);

                // This line caused the advancement screen etc to be green
                // RenderSystem.setShaderColor(0f, 1f, 0f, 1f);

                // (method_34540) 1.19.x: GameRenderer::getPositionColorShader => 1.20.x: GameRenderer::getPositionColorProgram
                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.enableBlend();

                Matrix4f matrix4f = context.getMatrices().peek().getPositionMatrix();
                Matrix3f matrix3f = context.getMatrices().peek().getNormalMatrix();

                // Broken in 1.20 GlStateManager._disableTexture();

                GlStateManager._depthMask(false);
                GlStateManager._disableCull();

                // (method_34535) 1.19.x: GameRenderer::getRenderTypeLinesShader => 1.20.x GameRenderer::getRenderTypeLinesProgram
                RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);

                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder bufferBuilder = tessellator.getBuffer();
                RenderSystem.lineWidth(2.0F);
                bufferBuilder.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);

                Consumer<DrawLineArguments> drawLine = a -> {
                    float normal_x = a.x2 - a.x;
                    float normal_y = a.y2 - a.y;
                    float length = (float) Math.sqrt(normal_x * normal_x + normal_y * normal_y);
                    if (Math.abs(length) < 0.0001) return;
                    normal_x /= length;
                    normal_y /= length;
                    bufferBuilder.vertex(matrix4f, a.x, a.y, -90.0f).color(a.r, a.g, a.b, a.a).normal(matrix3f, normal_x, normal_y, 0f).next();
                    bufferBuilder.vertex(matrix4f, a.x2, a.y2, -90.0f).color(a.r2, a.g2, a.b2, a.a2).normal(matrix3f, normal_x, normal_y, 0f).next();
                };

                // draw lines on the horizon UPWARDS. (-degrees in minecraft)
                for (int i = -90; i < 0; i += 10) {
                    // Out of upper screen bound. next!
                    // if (i < screen_upper_pitch_deg) continue;
                    double diff_deg = i - camera_pitch_deg; // -90 - -30 = -90 + 30 = -60
                    float diff_pixels = (float) (pixels_per_deg * diff_deg);
                    float height = screenCenterY + diff_pixels;
                    if (height > screenHeight * 0.85f || height < screenHeight * 0.15f) continue;

                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f, height, screenCenterX - horizon_width / 2f + horizon_width / 3, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f, height, screenCenterX - horizon_width / 2f, height + horizon_vertical_blip_length).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f + 2 * horizon_width / 3f, height, screenCenterX - horizon_width / 2f + horizon_width, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f + horizon_width, height, screenCenterX - horizon_width / 2f + horizon_width, height + horizon_vertical_blip_length).color(0f, 1f, 0f, 1f * hud_alpha));
                }

                // draw lines on the horizon UPWARDS. (-degrees in minecraft)
                for (int i = 90; i > 0; i -= 10) {
                    // Out of upper screen bound. next!
                    // if (i < screen_upper_pitch_deg) continue;
                    double diff_deg = i - camera_pitch_deg; // -90 - -30 = -90 + 30 = -60
                    float diff_pixels = (float) (pixels_per_deg * diff_deg);
                    float height = screenCenterY + diff_pixels;
                    if (height > screenHeight * 0.85f || height < screenHeight * 0.15f) continue;

                    // left horizontal lines
                    float leftx = screenCenterX - horizon_width / 2f;
                    float part_width = horizon_width / 3f / 3f;
                    drawLine.accept(DrawLineArguments.make(leftx, height, leftx + part_width - part_width / 3, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(leftx + part_width, height, leftx + 2 * part_width - part_width / 3, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(leftx + 2 * part_width, height, leftx + 3 * part_width, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    // right horizontal lines
                    float rightx = screenCenterX + horizon_width / 2f;
                    drawLine.accept(DrawLineArguments.make(rightx, height, rightx - part_width + part_width / 3, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(rightx - part_width, height, rightx - 2 * part_width + part_width / 3, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(rightx - 2 * part_width, height, rightx - 3 * part_width, height).color(0f, 1f, 0f, 1f * hud_alpha));
                    // vertical ends
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f, height, screenCenterX - horizon_width / 2f, height - horizon_vertical_blip_length).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f + horizon_width, height, screenCenterX - horizon_width / 2f + horizon_width, height - horizon_vertical_blip_length).color(0f, 1f, 0f, 1f * hud_alpha));
                }

                if (!(center_height > screenHeight * 0.85f || center_height < screenHeight * 0.15f)) {
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f, center_height, screenCenterX - horizon_width / 2f + horizon_width / 3, center_height).color(0f, 1f, 0f, 1f * hud_alpha));
                    drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width / 2f + 2 * horizon_width / 3f, center_height, screenCenterX + horizon_width / 2f, center_height).color(0f, 1f, 0f, 1f * hud_alpha));
                }

                // float flight_vector_size = screenLesser / 100f;
                float flight_vector_size = screenLesser / 70f;
                float flight_vector_radius = flight_vector_size / 2f;
                // drawLine.accept(DrawLineArguments.make(flight_vector_x - flight_vector_size / 2f, flight_vector_y - flight_vector_size / 2f, flight_vector_x + flight_vector_size / 2f, flight_vector_y - flight_vector_size / 2f).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x + flight_vector_size / 2f, flight_vector_y - flight_vector_size / 2f, flight_vector_x + flight_vector_size / 2f, flight_vector_y + flight_vector_size / 2f).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x + flight_vector_size / 2f, flight_vector_y + flight_vector_size / 2f, flight_vector_x - flight_vector_size / 2f, flight_vector_y + flight_vector_size / 2f).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x - flight_vector_size / 2f, flight_vector_y + flight_vector_size / 2f, flight_vector_x - flight_vector_size / 2f, flight_vector_y - flight_vector_size / 2f).color(0f, 1f, 0f, 1f * hud_alpha));

                for (DrawLineArguments line : circleLines(flight_vector_x, flight_vector_y, flight_vector_radius, 10)) {
                    drawLine.accept(line.color(0f, 1f, 0f, 1f * hud_alpha));
                }
                // drawLine.accept(DrawLineArguments.make(flight_vector_x, flight_vector_y - flight_vector_size/2f, flight_vector_x, flight_vector_y - flight_vector_size/2f - flight_vector_size*0.4f).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x - flight_vector_size/2f - flight_vector_size*0.5f, flight_vector_y, flight_vector_x - flight_vector_size/2f, flight_vector_y).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x + flight_vector_size/2f, flight_vector_y, flight_vector_x + flight_vector_size/2f + flight_vector_size*0.5f, flight_vector_y).color(0f, 1f, 0f, 1f * hud_alpha));
                float elytra_roll_deg = forwardSpeed < 0.0001f ? 0f : (float) (Math.min(2, Math.max(-2, sidewaysSpeed / forwardSpeed))) * -45f;
                float elytra_roll = (float) Math.toRadians(elytra_roll_deg);
                drawLine.accept(DrawLineArguments.make(
                        flight_vector_x + (float) Math.sin(elytra_roll) * flight_vector_radius,
                        flight_vector_y - (float) Math.cos(elytra_roll) * flight_vector_radius,
                        flight_vector_x + (float) Math.sin(elytra_roll) * (flight_vector_radius + flight_vector_size * 0.4f),
                        flight_vector_y - (float) Math.cos(elytra_roll) * (flight_vector_radius + flight_vector_size * 0.4f)
                ).color(0f, 1f, 0f, 1f * hud_alpha));

                drawLine.accept(DrawLineArguments.make(
                        flight_vector_x + (float) Math.sin(elytra_roll + 0.5 * Math.PI) * flight_vector_radius,
                        flight_vector_y - (float) Math.cos(elytra_roll + 0.5 * Math.PI) * flight_vector_radius,
                        flight_vector_x + (float) Math.sin(elytra_roll + 0.5 * Math.PI) * (flight_vector_radius + flight_vector_size * 0.5f),
                        flight_vector_y - (float) Math.cos(elytra_roll + 0.5 * Math.PI) * (flight_vector_radius + flight_vector_size * 0.5f)
                ).color(0f, 1f, 0f, 1f * hud_alpha));

                drawLine.accept(DrawLineArguments.make(
                        flight_vector_x + (float) Math.sin(elytra_roll - 0.5 * Math.PI) * flight_vector_radius,
                        flight_vector_y - (float) Math.cos(elytra_roll - 0.5 * Math.PI) * flight_vector_radius,
                        flight_vector_x + (float) Math.sin(elytra_roll - 0.5 * Math.PI) * (flight_vector_radius + flight_vector_size * 0.5f),
                        flight_vector_y - (float) Math.cos(elytra_roll - 0.5 * Math.PI) * (flight_vector_radius + flight_vector_size * 0.5f)
                ).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x - flight_vector_size/2f - flight_vector_size*0.5f, flight_vector_y, flight_vector_x - flight_vector_size/2f, flight_vector_y).color(0f, 1f, 0f, 1f * hud_alpha));
                // drawLine.accept(DrawLineArguments.make(flight_vector_x + flight_vector_size/2f, flight_vector_y, flight_vector_x + flight_vector_size/2f + flight_vector_size*0.5f, flight_vector_y).color(0f, 1f, 0f, 1f * hud_alpha));

                // drawLine.accept(DrawLineArguments.make(screenCenterX, screenCenterY, flight_vector_x, flight_vector_y).color(0f, 1f, 0f, 1f));
                double flight_vector_angle = Math.atan2(screenCenterY - flight_vector_y, screenCenterX - flight_vector_x);
                drawLine.accept(DrawLineArguments.make(screenCenterX + 0.66f * (flight_vector_x - screenCenterX), screenCenterY + 0.66f * (flight_vector_y - screenCenterY), flight_vector_x + (flight_vector_size / 2f) * (float) Math.cos(flight_vector_angle), flight_vector_y + (flight_vector_size / 2f) * (float) Math.sin(flight_vector_angle)).color(0f, 1f, 0f, 1f * hud_alpha));

                // drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width/2f, (float) screenCenterY, screenCenterX - horizon_width/2f + horizon_width/3, (float) screenCenterY).color(0f, 1f, 0f, 1f));
                // drawLine.accept(DrawLineArguments.make(screenCenterX - horizon_width/2f + 2*horizon_width/3f, (float) screenCenterY, screenCenterX - horizon_width/2f + horizon_width, (float) screenCenterY).color(0f, 1f, 0f, 1f));

                // bufferBuilder.vertex(matrix4f, screenCenterX - screenWidth/4.0f, screenCenterY - screenHeight/4.0f, -90.0f).color(0, 255, 0, 255).normal(matrix3f, 1.0F, 1.0F, 0.0F).next();
                // bufferBuilder.vertex(matrix4f, screenCenterX + screenWidth/4.0f, screenCenterY - screenHeight/4.0f, -90.0f).color(0, 255, 0, 255).normal(matrix3f, 1.0F, 1.0F, 0.0F).next();
                // bufferBuilder.vertex(0.0D, 0.0D, -90.0D).color(0, 255, 0, 255).normal(0.0F, 0.0F, 1.0F).next();
                // bufferBuilder.vertex((double)screenCenterX, (double)screenCenterY, -90.0D).color(0, 255, 0, 255).normal(0.0F, 0.0F, 1.0F).next();

                // Compass
                float heading = camera.getYaw();
                float compass_width = 0.85f * horizon_width;
                int heading_tens = Math.round(heading / 10f);
                float heading_fives = Math.round((heading * 2f) / 10f) / 2f;
                float compass_blip_height = screenHeight / 200f;

                for (float heading_blip = heading_fives - 3f * 0.5f; heading_blip <= heading / 10f + 3 * 0.5f; heading_blip += 0.5f) {
                    // Skip first blip if it's outside.
                    if (heading_blip < heading / 10f - 3 * 0.5f) continue;
                    float heading_offset = heading - (heading_blip * 10f);
                    float heading_x = screenCenterX - (heading_offset / 15f) * compass_width / 2f;
                    float font_height = textRenderer.fontHeight;
                    drawLine.accept(DrawLineArguments.make(heading_x, screenCenterY + screenHeight / 4f + font_height * 1.05f, heading_x, screenCenterY + screenHeight / 4f + font_height * 1.05f + compass_blip_height).color(0f, 1f, 0f, 1f * hud_alpha));
                }

                float compass_triangle_y = screenCenterY + screenHeight / 4f + textRenderer.fontHeight * 1.05f + compass_blip_height * 0.95f;
                float compass_triangle_height = compass_blip_height;
                float compass_triangle_width = compass_triangle_height * 2f;

                drawLine.accept(DrawLineArguments.make((float) screenCenterX, compass_triangle_y, screenCenterX + compass_triangle_width / 2f, compass_triangle_y + compass_triangle_height).color(0f, 1f, 0f, 1f * hud_alpha));
                drawLine.accept(DrawLineArguments.make((float) screenCenterX + compass_triangle_width / 2f, compass_triangle_y + compass_triangle_height, screenCenterX - compass_triangle_width / 2f, compass_triangle_y + compass_triangle_height).color(0f, 1f, 0f, 1f * hud_alpha));
                drawLine.accept(DrawLineArguments.make((float) screenCenterX - compass_triangle_width / 2f, compass_triangle_y + compass_triangle_height, screenCenterX, compass_triangle_y).color(0f, 1f, 0f, 1f * hud_alpha));
                // bufferBuilder.vertex(matrix4f, screenCenterX + compass_triangle_width/2f, compass_triangle_y + compass_triangle_height, -90f).color(0f, 1f, 0f, 1f * hud_alpha).next();
                // bufferBuilder.vertex(matrix4f, screenCenterX - compass_triangle_width/2f, compass_triangle_y + compass_triangle_height, -90f).color(0f, 1f, 0f, 1f * hud_alpha).next();

                // bufferBuilder.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
                // bufferBuilder.vertex(matrix4f, screenCenterX, compass_triangle_y, -90f).color(0f, 1f, 0f, 1f * hud_alpha).next();
                // bufferBuilder.vertex(matrix4f, screenCenterX + compass_triangle_width/2f, compass_triangle_y + compass_triangle_height, -90f).color(0f, 1f, 0f, 1f * hud_alpha).next();
                // bufferBuilder.vertex(matrix4f, screenCenterX - compass_triangle_width/2f, compass_triangle_y + compass_triangle_height, -90f).color(0f, 1f, 0f, 1f * hud_alpha).next();

                tessellator.draw();

                RenderSystem.lineWidth(1.0F);

                Vec3d player_velocity_vector = new Vec3d(player.getVelocity().x, player.getVelocity().y - Constants.GRAVITY, player.getVelocity().z);
                int air_speed = Math.round((float) player_velocity_vector.length() * 100f);

                if (hud_alpha > 0.66) {
                    for (int i = -90; i < 90; i += 10) {
                        if (i == 0) continue;
                        // Out of upper screen bound. next!
                        // if (i < screen_upper_pitch_deg) continue;
                        double diff_deg = i - camera_pitch_deg; // -90 - -30 = -90 + 30 = -60
                        float diff_pixels = (float) (pixels_per_deg * diff_deg);
                        // float height = screenCenterY + diff_pixels - screenHeight / 160f;
                        float height = screenCenterY + diff_pixels - (i > 0 ? (0.9f * textRenderer.fontHeight) : (textRenderer.fontHeight * (0.15f)));
                        if (height > screenHeight * 0.85f || height < screenHeight * 0.15f) continue;
                        String text = "" + Math.abs(i);
                        context.drawText(textRenderer, text, (int) (screenCenterX + horizon_width / 2f), (int) height, 0x00FF00, false);
                        context.drawText(textRenderer, text, (int) (screenCenterX - horizon_width / 2f - textRenderer.getWidth(text) - screenWidth / 500f), (int) height, 0x00FF00, false);
                    }

                    context.drawText(textRenderer, "" + ((int) Math.floor(player_pos.y)), (int)(screenCenterX + horizon_width * 0.8f), (int) screenCenterY, 0x00FF00, false);
                    // textRenderer.draw(stack, "" + (Math.round((float) player_velocity_vector.y * 10f)), screenCenterX + horizon_width * 0.8f, (float) screenCenterY + textRenderer.fontHeight * 1.5f, 0x00FF00);
                    int fall_distance_color = 0x00FF00;
                    if (player.fallDistance > player.getSafeFallDistance() * 2f) {
                        fall_distance_color = 0xFF0000;
                    } else if (player.fallDistance > player.getSafeFallDistance()) {
                        fall_distance_color = 0xFF8800;
                    } else if (player.fallDistance > player.getSafeFallDistance() * 0.75f) {
                        fall_distance_color = 0xFFFF00;
                    }

                    // Text that changes colours (green-black)
                    context.drawText(textRenderer, "" + (Math.round((float) player_velocity_vector.y * 10f)), (int)(screenCenterX + horizon_width * 0.8f), (int) (screenCenterY + textRenderer.fontHeight * 1.5f), fall_distance_color, false);

                    // Radarheight
                    context.drawText(textRenderer, radar_height + "R", (int)(screenCenterX + horizon_width * 0.75f), (int)(screenCenterY + screenHeight * (1f / 8f)), 0x00FF00, false);

                    // if (air_speed < 0.01) air_speed = 0;

                    int air_speed_color = 0x00FF00;
                    double collisionDamage = collisionDamageHorizontal(player);
                    if (collisionDamage > 5) {
                        air_speed_color = 0x88FF00;
                    } else if (collisionDamage > 0) {
                        air_speed_color = 0x44FF00;
                    }

                    // Airspeed
                    context.drawText(textRenderer, "" + air_speed, (int)(screenCenterX - horizon_width * 0.75f - textRenderer.getWidth("" + air_speed)), screenCenterY, air_speed_color, false);

                    for (float heading_blip = heading_fives - 3f * 0.5f; heading_blip <= heading / 10f + 3 * 0.5f; heading_blip += 0.5f) {
                        // Skip first blip if it's outside.
                        if (heading_blip < heading / 10f - 3 * 0.5f) continue;
                        if (Math.floor(heading_blip) == heading_blip) {
                            float heading_blip_360 = heading_blip < 0 ? (36 - realMod(heading_blip, 36)) : (heading_blip % 36);
                            if (heading_blip_360 == 36) heading_blip_360 = 0;
                            String heading_text = ((Math.floor(heading_blip_360) < 10) ? "0" : "") + (int) Math.floor(heading_blip_360);
                            float heading_offset = heading - (heading_blip * 10f);
                            float heading_x = screenCenterX - (heading_offset / 15f) * compass_width / 2f;
                            context.drawText(textRenderer, heading_text, (int)(heading_x - textRenderer.getWidth(heading_text) / 2f), (int) (screenCenterY + screenHeight / 4f), 0x00FF00, false);
                        }
                    }
                }

                GlStateManager._enableCull();
                GlStateManager._depthMask(true);
                // Broken in 1.20 GlStateManager._enableTexture();
            }
        }
    }

    /**
     * Calculates mod from signed floats
     * */
    private static float realMod(float a, float b) {
        float result = a % b;
        return result < 0 ? result + b : result;
    }

    /**
     * Calculates the lines for a circle with center x, y and a radius
     * by dividing the circle into small straight lines
     * */
    private static Collection<DrawLineArguments> circleLines(float x, float y, float radius, int parts) {
        ArrayList<DrawLineArguments> lines = new ArrayList<>(parts);

        for (double phi = 0D; phi < Constants.TWO_PI; phi += Constants.TWO_PI/parts) {
            double x1 = x + Math.cos(phi) * radius;
            double x2 = x + Math.cos(phi + Constants.TWO_PI/parts) * radius;
            double y1 = y + Math.sin(phi) * radius;
            double y2 = y + Math.sin(phi + Constants.TWO_PI/parts) * radius;

            lines.add(DrawLineArguments.make((float)x1, (float)y1, (float)x2, (float)y2));
        }

        return lines;
    }

    private static double collisionDamageHorizontal(PlayerEntity player) {
        Vec3d velocity = player.getVelocity();
        Vec3d multiplied_velocity = velocity.multiply(0.99f, 0.98f, 0.99f);
        return multiplied_velocity.horizontalLength() * 10.0 - player.getSafeFallDistance();
    }

    /**
     * Calculate how many air blocks are below the player
     * (= How high the player is really flying in relation to the ground)
     * */
    private static int getRadarHeight(ClientPlayerEntity player, ClientWorld world){
        int radar_height = 0;
        BlockPos player_blockpos = player.getBlockPos();

        if (world != null) {
            for (int y = player.getBlockPos().getY() - 1; y >= world.getBottomY() && world.isAir(new BlockPos(player_blockpos.getX(), y, player_blockpos.getZ())); y--) {
                ++radar_height;
            }
        }

        return radar_height;
    }
}
