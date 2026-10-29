#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// light = vertex rgb * vertex alpha * HEADROOM (keep in sync with LightRenderer.HEADROOM)
#define HEADROOM 4.0

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < 0.1) {
        discard;
    }
    float fog = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    // the light the screen throws onto the surface, added on top of how it is lit already
    vec3 light = albedo.rgb * vertexColor.rgb * (vertexColor.a * HEADROOM);
    fragColor = vec4(light * (1.0 - fog), 1.0) * ColorModulator;
}
