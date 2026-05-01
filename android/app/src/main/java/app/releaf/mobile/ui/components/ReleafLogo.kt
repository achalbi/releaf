/*
 * ReleafLogo.kt
 * Leaf brand mark, the user-supplied SVG mark (April 2026, high-res
 * version) embedded verbatim. The leaf body, stem, and vein cutouts
 * are baked into one closed subpath in a 526.456 × 778.24 working
 * viewport (the path bbox, translated to origin). Callers should NOT
 * apply an extra rotation; the Compose Path normalizes by 778.24 (the
 * path's larger dimension) and centers the 526-wide silhouette in
 * a square frame.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors

/**
 * Build the canonical leaf body path at the caller's resolved size.
 * Numbers are the 526.456x778.24-viewport SVG scaled by `scale = size / 778.24`,
 * with x shifted to center the 526-wide silhouette in a square.
 */
private fun leafBody(scale: Float): Path = Path().apply {
    val dx = (778.24f - 526.456f) / 2f
    fun x(n: Float) = (n + dx) * scale
    fun y(n: Float) = n * scale
    moveTo(x(499.984f), y(0f))
    cubicTo(x(502.279f), y(2.295f), x(502.424f), y(3.219f), x(503.027f), y(6.336f))
    cubicTo(x(503.21f), y(7.276f), x(503.393f), y(8.216f), x(503.582f), y(9.185f))
    cubicTo(x(503.777f), y(10.237f), x(503.971f), y(11.29f), x(504.172f), y(12.375f))
    cubicTo(x(504.486f), y(14.047f), x(504.486f), y(14.047f), x(504.808f), y(15.753f))
    cubicTo(x(506.92f), y(27.222f), x(508.772f), y(38.734f), x(510.609f), y(50.25f))
    cubicTo(x(510.755f), y(51.163f), x(510.901f), y(52.075f), x(511.051f), y(53.015f))
    cubicTo(x(514.304f), y(73.426f), x(517.193f), y(93.748f), x(518.609f), y(114.375f))
    cubicTo(x(518.731f), y(116.066f), x(518.853f), y(117.758f), x(518.976f), y(119.449f))
    cubicTo(x(519.273f), y(123.55f), x(519.553f), y(127.652f), x(519.829f), y(131.754f))
    cubicTo(x(519.971f), y(133.807f), x(520.126f), y(135.859f), x(520.283f), y(137.912f))
    cubicTo(x(526.456f), y(223.418f), x(514.564f), y(314.228f), x(478.184f), y(392.417f))
    cubicTo(x(476.979f), y(395.01f), x(475.805f), y(397.615f), x(474.632f), y(400.223f))
    cubicTo(x(462.847f), y(426.072f), x(447.523f), y(450.656f), x(429.984f), y(473f))
    cubicTo(x(429.356f), y(473.808f), x(428.728f), y(474.616f), x(428.082f), y(475.449f))
    cubicTo(x(409.752f), y(498.727f), x(389.358f), y(520.892f), x(365.634f), y(538.749f))
    cubicTo(x(363.991f), y(539.994f), x(362.37f), y(541.264f), x(360.754f), y(542.543f))
    cubicTo(x(342.656f), y(556.812f), x(322.781f), y(569.169f), x(301.967f), y(579.056f))
    cubicTo(x(299.897f), y(580.042f), x(297.837f), y(581.047f), x(295.777f), y(582.055f))
    cubicTo(x(286.358f), y(586.62f), x(276.833f), y(590.459f), x(266.984f), y(594f))
    cubicTo(x(265.712f), y(594.486f), x(264.439f), y(594.973f), x(263.168f), y(595.461f))
    cubicTo(x(227.96f), y(608.68f), x(188.93f), y(615.833f), x(151.347f), y(616.316f))
    cubicTo(x(149.973f), y(616.337f), x(148.599f), y(616.358f), x(147.224f), y(616.379f))
    cubicTo(x(143.646f), y(616.434f), x(140.067f), y(616.484f), x(136.488f), y(616.532f))
    cubicTo(x(132.821f), y(616.583f), x(129.153f), y(616.638f), x(125.486f), y(616.693f))
    cubicTo(x(118.319f), y(616.8f), x(111.151f), y(616.902f), x(103.984f), y(617f))
    cubicTo(x(97.382f), y(629.933f), x(91.018f), y(642.884f), x(85.484f), y(656.312f))
    cubicTo(x(85.172f), y(657.063f), x(84.861f), y(657.814f), x(84.54f), y(658.588f))
    cubicTo(x(75.267f), y(681.007f), x(66.877f), y(704.194f), x(61.984f), y(728f))
    cubicTo(x(61.835f), y(728.71f), x(61.687f), y(729.43f), x(61.533f), y(730.17f))
    cubicTo(x(58.815f), y(743.64f), x(57.911f), y(757.3f), x(56.984f), y(771f))
    cubicTo(x(44.612f), y(774.76f), x(27.236f), y(778.24f), x(14.984f), y(773f))
    cubicTo(x(13.789f), y(743.25f), x(18.792f), y(716.38f), x(27.984f), y(688f))
    cubicTo(x(28.436f), y(686.601f), x(28.436f), y(686.601f), x(28.897f), y(685.173f))
    cubicTo(x(40.154f), y(650.76f), x(55.338f), y(617.482f), x(71.824f), y(585.28f))
    cubicTo(x(72.894f), y(583.178f), x(73.942f), y(581.067f), x(74.98f), y(578.949f))
    cubicTo(x(91.03f), y(546.216f), x(109.569f), y(514.515f), x(130.984f), y(485f))
    cubicTo(x(131.538f), y(484.232f), x(132.093f), y(483.463f), x(132.664f), y(482.671f))
    cubicTo(x(146.762f), y(463.162f), x(161.928f), y(444.547f), x(178.316f), y(426.914f))
    cubicTo(x(180.306f), y(424.741f), x(182.24f), y(422.548f), x(184.161f), y(420.316f))
    cubicTo(x(187.888f), y(416.052f), x(191.814f), y(412.025f), x(195.824f), y(408.027f))
    cubicTo(x(196.55f), y(407.301f), x(197.275f), y(406.574f), x(198.023f), y(405.825f))
    cubicTo(x(199.547f), y(404.3f), x(201.073f), y(402.777f), x(202.599f), y(401.255f))
    cubicTo(x(204.915f), y(398.944f), x(207.226f), y(396.627f), x(209.537f), y(394.311f))
    cubicTo(x(211.028f), y(392.82f), x(212.52f), y(391.33f), x(214.011f), y(389.84f))
    cubicTo(x(215.032f), y(388.816f), x(215.032f), y(388.816f), x(216.073f), y(387.772f))
    cubicTo(x(219.99f), y(383.874f), x(224.008f), y(380.151f), x(228.198f), y(376.548f))
    cubicTo(x(230.967f), y(374.148f), x(233.624f), y(371.632f), x(236.297f), y(369.125f))
    cubicTo(x(241.122f), y(364.631f), x(246.094f), y(360.406f), x(251.238f), y(356.277f))
    cubicTo(x(254.247f), y(353.782f), x(257.123f), y(351.162f), x(259.984f), y(348.5f))
    cubicTo(x(264.537f), y(344.269f), x(269.233f), y(340.278f), x(274.07f), y(336.375f))
    cubicTo(x(282.734f), y(329.313f), x(291.104f), y(321.931f), x(299.332f), y(314.367f))
    cubicTo(x(302.025f), y(311.964f), x(304.793f), y(309.695f), x(307.609f), y(307.438f))
    cubicTo(x(311.9f), y(303.976f), x(315.813f), y(300.32f), x(319.621f), y(296.336f))
    cubicTo(x(321.57f), y(294.409f), x(323.548f), y(292.631f), x(325.625f), y(290.848f))
    cubicTo(x(330.659f), y(286.479f), x(335.346f), y(281.785f), x(340.047f), y(277.062f))
    cubicTo(x(340.906f), y(276.207f), x(341.765f), y(275.352f), x(342.65f), y(274.471f))
    cubicTo(x(347.638f), y(269.475f), x(352.4f), y(264.366f), x(356.984f), y(259f))
    cubicTo(x(357.957f), y(257.893f), x(358.93f), y(256.788f), x(359.906f), y(255.684f))
    cubicTo(x(378.128f), y(234.86f), x(393.731f), y(211.302f), x(404.984f), y(186f))
    cubicTo(x(403.82f), y(187.172f), x(403.82f), y(187.172f), x(402.632f), y(188.367f))
    cubicTo(x(399.72f), y(191.298f), x(396.805f), y(194.225f), x(393.888f), y(197.151f))
    cubicTo(x(392.632f), y(198.412f), x(391.377f), y(199.674f), x(390.123f), y(200.936f))
    cubicTo(x(383.588f), y(207.515f), x(377.075f), y(214.014f), x(369.984f), y(220f))
    cubicTo(x(368.376f), y(221.454f), x(366.772f), y(222.912f), x(365.172f), y(224.375f))
    cubicTo(x(359.923f), y(229.106f), x(354.479f), y(233.56f), x(348.984f), y(238f))
    cubicTo(x(347.765f), y(238.999f), x(346.546f), y(239.999f), x(345.328f), y(241f))
    cubicTo(x(336.464f), y(248.268f), x(327.451f), y(255.308f), x(318.315f), y(262.229f))
    cubicTo(x(315.816f), y(264.128f), x(313.327f), y(266.039f), x(310.839f), y(267.953f))
    cubicTo(x(302.039f), y(274.713f), x(293.19f), y(281.355f), x(284.088f), y(287.703f))
    cubicTo(x(277.261f), y(292.473f), x(270.615f), y(297.468f), x(263.977f), y(302.496f))
    cubicTo(x(261.962f), y(304.016f), x(259.941f), y(305.527f), x(257.918f), y(307.035f))
    cubicTo(x(249.747f), y(313.126f), x(241.677f), y(319.326f), x(233.689f), y(325.654f))
    cubicTo(x(231.997f), y(326.99f), x(230.298f), y(328.317f), x(228.597f), y(329.641f))
    cubicTo(x(216.473f), y(339.086f), x(204.435f), y(348.681f), x(193.097f), y(359.066f))
    cubicTo(x(191.127f), y(360.869f), x(189.128f), y(362.628f), x(187.109f), y(364.375f))
    cubicTo(x(177.217f), y(373.017f), x(167.769f), y(382.052f), x(158.5f), y(391.353f))
    cubicTo(x(156.659f), y(393.2f), x(154.813f), y(395.04f), x(152.966f), y(396.881f))
    cubicTo(x(147.092f), y(402.752f), x(141.344f), y(408.649f), x(135.984f), y(415f))
    cubicTo(x(134.928f), y(416.204f), x(133.869f), y(417.406f), x(132.808f), y(418.605f))
    cubicTo(x(125.871f), y(426.508f), x(119.231f), y(434.535f), x(112.984f), y(443f))
    cubicTo(x(112.479f), y(443.685f), x(111.973f), y(444.369f), x(111.453f), y(445.075f))
    cubicTo(x(99.363f), y(461.521f), x(88.248f), y(478.353f), x(77.984f), y(496f))
    cubicTo(x(77.524f), y(496.79f), x(77.064f), y(497.579f), x(76.589f), y(498.393f))
    cubicTo(x(62.88f), y(522.114f), x(50.667f), y(547.951f), x(41.984f), y(574f))
    cubicTo(x(38.447f), y(572.821f), x(38.191f), y(572.166f), x(36.382f), y(569.047f))
    cubicTo(x(35.897f), y(568.211f), x(35.411f), y(567.376f), x(34.911f), y(566.515f))
    cubicTo(x(34.399f), y(565.602f), x(33.887f), y(564.69f), x(33.359f), y(563.75f))
    cubicTo(x(32.826f), y(562.803f), x(32.294f), y(561.857f), x(31.745f), y(560.882f))
    cubicTo(x(21.924f), y(543.097f), x(15.448f), y(524.526f), x(9.984f), y(505f))
    cubicTo(x(9.646f), y(503.794f), x(9.646f), y(503.794f), x(9.3f), y(502.563f))
    cubicTo(x(5.933f), y(490.258f), x(4.336f), y(477.667f), x(2.984f), y(465f))
    cubicTo(x(2.888f), y(464.124f), x(2.791f), y(463.248f), x(2.692f), y(462.345f))
    cubicTo(x(0f), y(434.71f), x(1.16f), y(406.306f), x(5.984f), y(379f))
    cubicTo(x(6.257f), y(377.424f), x(6.257f), y(377.424f), x(6.535f), y(375.816f))
    cubicTo(x(10.732f), y(352.265f), x(17.172f), y(328.845f), x(26.984f), y(307f))
    cubicTo(x(27.417f), y(306.035f), x(27.85f), y(305.071f), x(28.296f), y(304.077f))
    cubicTo(x(37.701f), y(283.371f), x(48.415f), y(263.669f), x(62.561f), y(245.809f))
    cubicTo(x(63.948f), y(244.045f), x(65.304f), y(242.262f), x(66.652f), y(240.469f))
    cubicTo(x(73.797f), y(231.044f), x(81.761f), y(222.488f), x(89.984f), y(214f))
    cubicTo(x(90.533f), y(213.429f), x(91.081f), y(212.859f), x(91.647f), y(212.271f))
    cubicTo(x(97.587f), y(206.11f), x(103.714f), y(200.389f), x(110.429f), y(195.062f))
    cubicTo(x(112.731f), y(193.204f), x(114.885f), y(191.268f), x(117.047f), y(189.25f))
    cubicTo(x(134.453f), y(173.664f), x(155.41f), y(161.092f), x(175.939f), y(150.103f))
    cubicTo(x(177.825f), y(149.086f), x(179.695f), y(148.045f), x(181.562f), y(146.992f))
    cubicTo(x(217.717f), y(126.969f), x(259.679f), y(112.867f), x(299.508f), y(102.228f))
    cubicTo(x(340.116f), y(91.236f), x(381.549f), y(76.504f), x(418.878f), y(57.088f))
    cubicTo(x(421.219f), y(55.879f), x(423.574f), y(54.697f), x(425.929f), y(53.516f))
    cubicTo(x(442.969f), y(44.883f), x(458.965f), y(34.815f), x(473.984f), y(23f))
    cubicTo(x(474.805f), y(22.371f), x(475.626f), y(21.742f), x(476.472f), y(21.094f))
    cubicTo(x(484.761f), y(14.643f), x(492.633f), y(7.505f), x(499.984f), y(0f))
    close()
}

@Composable
fun ReleafLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    filled: Boolean = true,
    outlineColor: Color = AppColors.OnAccent,
    fillGradientStart: Color = AppAccent.primary,
    fillGradientEnd: Color = AppAccent.deep,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 778.24f
        val body = leafBody(scale)
        val sw = strokeWidth.toPx()

        if (filled) {
            val gradient = Brush.linearGradient(
                colors = listOf(fillGradientStart, fillGradientEnd),
                start = Offset(this.size.width / 2f, 0f),
                end   = Offset(this.size.width / 2f, this.size.height),
            )
            drawPath(body, brush = gradient)
        } else {
            drawPath(
                body,
                color = outlineColor,
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Solid-color filled leaf — used in app-icon and splash contexts where
 * the leaf is a single cream silhouette on a deep-green plate.
 */
@Composable
fun ReleafLogoSolid(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    leafColor: Color = AppColors.OnAccent,
    veinColor: Color = AppAccent.deep,
    veinWidth: Dp = 3.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 778.24f
        drawPath(leafBody(scale), color = leafColor)
    }
}

/**
 * Outline-only variant used where the leaf sits on a colored plate
 * (e.g. the Releaf splash). The body fill is skipped so the plate
 * color shows through the leaf.
 */
@Composable
fun ReleafLogoOutline(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    color: Color = AppColors.OnAccent,
    strokeWidth: Dp = 3.dp,
) {
    ReleafLogo(
        modifier = modifier,
        size = size,
        filled = false,
        outlineColor = color,
        strokeWidth = strokeWidth,
    )
}
