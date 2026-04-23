#version 150

uniform sampler2D Sampler0;
uniform vec2 ScreenSize;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec4 sampledColor = texture(Sampler0, uv);
    fragColor = sampledColor * vertexColor;
}
