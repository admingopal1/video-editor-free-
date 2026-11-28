#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uMaskCenter;
uniform vec2 uMaskHalfSize;
uniform float uMaskShape;
uniform float uMaskFeather;
uniform float uMaskInvert;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec2 delta = abs(vTexSamplingCoord - uMaskCenter);
  float signedDistance;
  if (uMaskShape < 0.5) signedDistance = min(uMaskHalfSize.x - delta.x, uMaskHalfSize.y - delta.y);
  else signedDistance = (1.0 - length(delta / uMaskHalfSize)) * min(uMaskHalfSize.x, uMaskHalfSize.y);
  float maskAlpha = uMaskFeather <= 0.0001 ? step(0.0, signedDistance) : smoothstep(-uMaskFeather, uMaskFeather, signedDistance);
  if (uMaskInvert > 0.5) maskAlpha = 1.0 - maskAlpha;
  gl_FragColor = vec4(src.rgb, src.a * maskAlpha);
}
