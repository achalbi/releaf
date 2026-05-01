/*
 * ReleafLogo.swift
 * Leaf brand mark, the user-supplied SVG mark (April 2026, high-res
 * version) embedded verbatim. The leaf body, stem, and vein cutouts
 * are baked into one closed subpath in a 526.456 × 778.24 working
 * viewport (the path bbox, translated to origin). Callers should NOT
 * apply additional rotation; the Shape normalizes by 778.24 (the
 * path's larger dimension) and centers the 526-wide silhouette in
 * a square frame.
 */

import SwiftUI

public struct ReleafLeafBody: Shape {
    public init() {}

    public func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 778.24
        // Shift x so the 99-wide leaf is centered in a square frame.
        let dx: CGFloat = (778.24 - 526.456) / 2
        func x(_ n: CGFloat) -> CGFloat { (n + dx) * s }
        func y(_ n: CGFloat) -> CGFloat { n * s }

        var p = Path()
        p.move(to: CGPoint(x: x(499.984), y: y(0)))
        p.addCurve(to: CGPoint(x: x(503.027), y: y(6.336)), control1: CGPoint(x: x(502.279), y: y(2.295)), control2: CGPoint(x: x(502.424), y: y(3.219)))
        p.addCurve(to: CGPoint(x: x(503.582), y: y(9.185)), control1: CGPoint(x: x(503.21), y: y(7.276)), control2: CGPoint(x: x(503.393), y: y(8.216)))
        p.addCurve(to: CGPoint(x: x(504.172), y: y(12.375)), control1: CGPoint(x: x(503.777), y: y(10.237)), control2: CGPoint(x: x(503.971), y: y(11.29)))
        p.addCurve(to: CGPoint(x: x(504.808), y: y(15.753)), control1: CGPoint(x: x(504.486), y: y(14.047)), control2: CGPoint(x: x(504.486), y: y(14.047)))
        p.addCurve(to: CGPoint(x: x(510.609), y: y(50.25)), control1: CGPoint(x: x(506.92), y: y(27.222)), control2: CGPoint(x: x(508.772), y: y(38.734)))
        p.addCurve(to: CGPoint(x: x(511.051), y: y(53.015)), control1: CGPoint(x: x(510.755), y: y(51.163)), control2: CGPoint(x: x(510.901), y: y(52.075)))
        p.addCurve(to: CGPoint(x: x(518.609), y: y(114.375)), control1: CGPoint(x: x(514.304), y: y(73.426)), control2: CGPoint(x: x(517.193), y: y(93.748)))
        p.addCurve(to: CGPoint(x: x(518.976), y: y(119.449)), control1: CGPoint(x: x(518.731), y: y(116.066)), control2: CGPoint(x: x(518.853), y: y(117.758)))
        p.addCurve(to: CGPoint(x: x(519.829), y: y(131.754)), control1: CGPoint(x: x(519.273), y: y(123.55)), control2: CGPoint(x: x(519.553), y: y(127.652)))
        p.addCurve(to: CGPoint(x: x(520.283), y: y(137.912)), control1: CGPoint(x: x(519.971), y: y(133.807)), control2: CGPoint(x: x(520.126), y: y(135.859)))
        p.addCurve(to: CGPoint(x: x(478.184), y: y(392.417)), control1: CGPoint(x: x(526.456), y: y(223.418)), control2: CGPoint(x: x(514.564), y: y(314.228)))
        p.addCurve(to: CGPoint(x: x(474.632), y: y(400.223)), control1: CGPoint(x: x(476.979), y: y(395.01)), control2: CGPoint(x: x(475.805), y: y(397.615)))
        p.addCurve(to: CGPoint(x: x(429.984), y: y(473)), control1: CGPoint(x: x(462.847), y: y(426.072)), control2: CGPoint(x: x(447.523), y: y(450.656)))
        p.addCurve(to: CGPoint(x: x(428.082), y: y(475.449)), control1: CGPoint(x: x(429.356), y: y(473.808)), control2: CGPoint(x: x(428.728), y: y(474.616)))
        p.addCurve(to: CGPoint(x: x(365.634), y: y(538.749)), control1: CGPoint(x: x(409.752), y: y(498.727)), control2: CGPoint(x: x(389.358), y: y(520.892)))
        p.addCurve(to: CGPoint(x: x(360.754), y: y(542.543)), control1: CGPoint(x: x(363.991), y: y(539.994)), control2: CGPoint(x: x(362.37), y: y(541.264)))
        p.addCurve(to: CGPoint(x: x(301.967), y: y(579.056)), control1: CGPoint(x: x(342.656), y: y(556.812)), control2: CGPoint(x: x(322.781), y: y(569.169)))
        p.addCurve(to: CGPoint(x: x(295.777), y: y(582.055)), control1: CGPoint(x: x(299.897), y: y(580.042)), control2: CGPoint(x: x(297.837), y: y(581.047)))
        p.addCurve(to: CGPoint(x: x(266.984), y: y(594)), control1: CGPoint(x: x(286.358), y: y(586.62)), control2: CGPoint(x: x(276.833), y: y(590.459)))
        p.addCurve(to: CGPoint(x: x(263.168), y: y(595.461)), control1: CGPoint(x: x(265.712), y: y(594.486)), control2: CGPoint(x: x(264.439), y: y(594.973)))
        p.addCurve(to: CGPoint(x: x(151.347), y: y(616.316)), control1: CGPoint(x: x(227.96), y: y(608.68)), control2: CGPoint(x: x(188.93), y: y(615.833)))
        p.addCurve(to: CGPoint(x: x(147.224), y: y(616.379)), control1: CGPoint(x: x(149.973), y: y(616.337)), control2: CGPoint(x: x(148.599), y: y(616.358)))
        p.addCurve(to: CGPoint(x: x(136.488), y: y(616.532)), control1: CGPoint(x: x(143.646), y: y(616.434)), control2: CGPoint(x: x(140.067), y: y(616.484)))
        p.addCurve(to: CGPoint(x: x(125.486), y: y(616.693)), control1: CGPoint(x: x(132.821), y: y(616.583)), control2: CGPoint(x: x(129.153), y: y(616.638)))
        p.addCurve(to: CGPoint(x: x(103.984), y: y(617)), control1: CGPoint(x: x(118.319), y: y(616.8)), control2: CGPoint(x: x(111.151), y: y(616.902)))
        p.addCurve(to: CGPoint(x: x(85.484), y: y(656.312)), control1: CGPoint(x: x(97.382), y: y(629.933)), control2: CGPoint(x: x(91.018), y: y(642.884)))
        p.addCurve(to: CGPoint(x: x(84.54), y: y(658.588)), control1: CGPoint(x: x(85.172), y: y(657.063)), control2: CGPoint(x: x(84.861), y: y(657.814)))
        p.addCurve(to: CGPoint(x: x(61.984), y: y(728)), control1: CGPoint(x: x(75.267), y: y(681.007)), control2: CGPoint(x: x(66.877), y: y(704.194)))
        p.addCurve(to: CGPoint(x: x(61.533), y: y(730.17)), control1: CGPoint(x: x(61.835), y: y(728.71)), control2: CGPoint(x: x(61.687), y: y(729.43)))
        p.addCurve(to: CGPoint(x: x(56.984), y: y(771)), control1: CGPoint(x: x(58.815), y: y(743.64)), control2: CGPoint(x: x(57.911), y: y(757.3)))
        p.addCurve(to: CGPoint(x: x(14.984), y: y(773)), control1: CGPoint(x: x(44.612), y: y(774.76)), control2: CGPoint(x: x(27.236), y: y(778.24)))
        p.addCurve(to: CGPoint(x: x(27.984), y: y(688)), control1: CGPoint(x: x(13.789), y: y(743.25)), control2: CGPoint(x: x(18.792), y: y(716.38)))
        p.addCurve(to: CGPoint(x: x(28.897), y: y(685.173)), control1: CGPoint(x: x(28.436), y: y(686.601)), control2: CGPoint(x: x(28.436), y: y(686.601)))
        p.addCurve(to: CGPoint(x: x(71.824), y: y(585.28)), control1: CGPoint(x: x(40.154), y: y(650.76)), control2: CGPoint(x: x(55.338), y: y(617.482)))
        p.addCurve(to: CGPoint(x: x(74.98), y: y(578.949)), control1: CGPoint(x: x(72.894), y: y(583.178)), control2: CGPoint(x: x(73.942), y: y(581.067)))
        p.addCurve(to: CGPoint(x: x(130.984), y: y(485)), control1: CGPoint(x: x(91.03), y: y(546.216)), control2: CGPoint(x: x(109.569), y: y(514.515)))
        p.addCurve(to: CGPoint(x: x(132.664), y: y(482.671)), control1: CGPoint(x: x(131.538), y: y(484.232)), control2: CGPoint(x: x(132.093), y: y(483.463)))
        p.addCurve(to: CGPoint(x: x(178.316), y: y(426.914)), control1: CGPoint(x: x(146.762), y: y(463.162)), control2: CGPoint(x: x(161.928), y: y(444.547)))
        p.addCurve(to: CGPoint(x: x(184.161), y: y(420.316)), control1: CGPoint(x: x(180.306), y: y(424.741)), control2: CGPoint(x: x(182.24), y: y(422.548)))
        p.addCurve(to: CGPoint(x: x(195.824), y: y(408.027)), control1: CGPoint(x: x(187.888), y: y(416.052)), control2: CGPoint(x: x(191.814), y: y(412.025)))
        p.addCurve(to: CGPoint(x: x(198.023), y: y(405.825)), control1: CGPoint(x: x(196.55), y: y(407.301)), control2: CGPoint(x: x(197.275), y: y(406.574)))
        p.addCurve(to: CGPoint(x: x(202.599), y: y(401.255)), control1: CGPoint(x: x(199.547), y: y(404.3)), control2: CGPoint(x: x(201.073), y: y(402.777)))
        p.addCurve(to: CGPoint(x: x(209.537), y: y(394.311)), control1: CGPoint(x: x(204.915), y: y(398.944)), control2: CGPoint(x: x(207.226), y: y(396.627)))
        p.addCurve(to: CGPoint(x: x(214.011), y: y(389.84)), control1: CGPoint(x: x(211.028), y: y(392.82)), control2: CGPoint(x: x(212.52), y: y(391.33)))
        p.addCurve(to: CGPoint(x: x(216.073), y: y(387.772)), control1: CGPoint(x: x(215.032), y: y(388.816)), control2: CGPoint(x: x(215.032), y: y(388.816)))
        p.addCurve(to: CGPoint(x: x(228.198), y: y(376.548)), control1: CGPoint(x: x(219.99), y: y(383.874)), control2: CGPoint(x: x(224.008), y: y(380.151)))
        p.addCurve(to: CGPoint(x: x(236.297), y: y(369.125)), control1: CGPoint(x: x(230.967), y: y(374.148)), control2: CGPoint(x: x(233.624), y: y(371.632)))
        p.addCurve(to: CGPoint(x: x(251.238), y: y(356.277)), control1: CGPoint(x: x(241.122), y: y(364.631)), control2: CGPoint(x: x(246.094), y: y(360.406)))
        p.addCurve(to: CGPoint(x: x(259.984), y: y(348.5)), control1: CGPoint(x: x(254.247), y: y(353.782)), control2: CGPoint(x: x(257.123), y: y(351.162)))
        p.addCurve(to: CGPoint(x: x(274.07), y: y(336.375)), control1: CGPoint(x: x(264.537), y: y(344.269)), control2: CGPoint(x: x(269.233), y: y(340.278)))
        p.addCurve(to: CGPoint(x: x(299.332), y: y(314.367)), control1: CGPoint(x: x(282.734), y: y(329.313)), control2: CGPoint(x: x(291.104), y: y(321.931)))
        p.addCurve(to: CGPoint(x: x(307.609), y: y(307.438)), control1: CGPoint(x: x(302.025), y: y(311.964)), control2: CGPoint(x: x(304.793), y: y(309.695)))
        p.addCurve(to: CGPoint(x: x(319.621), y: y(296.336)), control1: CGPoint(x: x(311.9), y: y(303.976)), control2: CGPoint(x: x(315.813), y: y(300.32)))
        p.addCurve(to: CGPoint(x: x(325.625), y: y(290.848)), control1: CGPoint(x: x(321.57), y: y(294.409)), control2: CGPoint(x: x(323.548), y: y(292.631)))
        p.addCurve(to: CGPoint(x: x(340.047), y: y(277.062)), control1: CGPoint(x: x(330.659), y: y(286.479)), control2: CGPoint(x: x(335.346), y: y(281.785)))
        p.addCurve(to: CGPoint(x: x(342.65), y: y(274.471)), control1: CGPoint(x: x(340.906), y: y(276.207)), control2: CGPoint(x: x(341.765), y: y(275.352)))
        p.addCurve(to: CGPoint(x: x(356.984), y: y(259)), control1: CGPoint(x: x(347.638), y: y(269.475)), control2: CGPoint(x: x(352.4), y: y(264.366)))
        p.addCurve(to: CGPoint(x: x(359.906), y: y(255.684)), control1: CGPoint(x: x(357.957), y: y(257.893)), control2: CGPoint(x: x(358.93), y: y(256.788)))
        p.addCurve(to: CGPoint(x: x(404.984), y: y(186)), control1: CGPoint(x: x(378.128), y: y(234.86)), control2: CGPoint(x: x(393.731), y: y(211.302)))
        p.addCurve(to: CGPoint(x: x(402.632), y: y(188.367)), control1: CGPoint(x: x(403.82), y: y(187.172)), control2: CGPoint(x: x(403.82), y: y(187.172)))
        p.addCurve(to: CGPoint(x: x(393.888), y: y(197.151)), control1: CGPoint(x: x(399.72), y: y(191.298)), control2: CGPoint(x: x(396.805), y: y(194.225)))
        p.addCurve(to: CGPoint(x: x(390.123), y: y(200.936)), control1: CGPoint(x: x(392.632), y: y(198.412)), control2: CGPoint(x: x(391.377), y: y(199.674)))
        p.addCurve(to: CGPoint(x: x(369.984), y: y(220)), control1: CGPoint(x: x(383.588), y: y(207.515)), control2: CGPoint(x: x(377.075), y: y(214.014)))
        p.addCurve(to: CGPoint(x: x(365.172), y: y(224.375)), control1: CGPoint(x: x(368.376), y: y(221.454)), control2: CGPoint(x: x(366.772), y: y(222.912)))
        p.addCurve(to: CGPoint(x: x(348.984), y: y(238)), control1: CGPoint(x: x(359.923), y: y(229.106)), control2: CGPoint(x: x(354.479), y: y(233.56)))
        p.addCurve(to: CGPoint(x: x(345.328), y: y(241)), control1: CGPoint(x: x(347.765), y: y(238.999)), control2: CGPoint(x: x(346.546), y: y(239.999)))
        p.addCurve(to: CGPoint(x: x(318.315), y: y(262.229)), control1: CGPoint(x: x(336.464), y: y(248.268)), control2: CGPoint(x: x(327.451), y: y(255.308)))
        p.addCurve(to: CGPoint(x: x(310.839), y: y(267.953)), control1: CGPoint(x: x(315.816), y: y(264.128)), control2: CGPoint(x: x(313.327), y: y(266.039)))
        p.addCurve(to: CGPoint(x: x(284.088), y: y(287.703)), control1: CGPoint(x: x(302.039), y: y(274.713)), control2: CGPoint(x: x(293.19), y: y(281.355)))
        p.addCurve(to: CGPoint(x: x(263.977), y: y(302.496)), control1: CGPoint(x: x(277.261), y: y(292.473)), control2: CGPoint(x: x(270.615), y: y(297.468)))
        p.addCurve(to: CGPoint(x: x(257.918), y: y(307.035)), control1: CGPoint(x: x(261.962), y: y(304.016)), control2: CGPoint(x: x(259.941), y: y(305.527)))
        p.addCurve(to: CGPoint(x: x(233.689), y: y(325.654)), control1: CGPoint(x: x(249.747), y: y(313.126)), control2: CGPoint(x: x(241.677), y: y(319.326)))
        p.addCurve(to: CGPoint(x: x(228.597), y: y(329.641)), control1: CGPoint(x: x(231.997), y: y(326.99)), control2: CGPoint(x: x(230.298), y: y(328.317)))
        p.addCurve(to: CGPoint(x: x(193.097), y: y(359.066)), control1: CGPoint(x: x(216.473), y: y(339.086)), control2: CGPoint(x: x(204.435), y: y(348.681)))
        p.addCurve(to: CGPoint(x: x(187.109), y: y(364.375)), control1: CGPoint(x: x(191.127), y: y(360.869)), control2: CGPoint(x: x(189.128), y: y(362.628)))
        p.addCurve(to: CGPoint(x: x(158.5), y: y(391.353)), control1: CGPoint(x: x(177.217), y: y(373.017)), control2: CGPoint(x: x(167.769), y: y(382.052)))
        p.addCurve(to: CGPoint(x: x(152.966), y: y(396.881)), control1: CGPoint(x: x(156.659), y: y(393.2)), control2: CGPoint(x: x(154.813), y: y(395.04)))
        p.addCurve(to: CGPoint(x: x(135.984), y: y(415)), control1: CGPoint(x: x(147.092), y: y(402.752)), control2: CGPoint(x: x(141.344), y: y(408.649)))
        p.addCurve(to: CGPoint(x: x(132.808), y: y(418.605)), control1: CGPoint(x: x(134.928), y: y(416.204)), control2: CGPoint(x: x(133.869), y: y(417.406)))
        p.addCurve(to: CGPoint(x: x(112.984), y: y(443)), control1: CGPoint(x: x(125.871), y: y(426.508)), control2: CGPoint(x: x(119.231), y: y(434.535)))
        p.addCurve(to: CGPoint(x: x(111.453), y: y(445.075)), control1: CGPoint(x: x(112.479), y: y(443.685)), control2: CGPoint(x: x(111.973), y: y(444.369)))
        p.addCurve(to: CGPoint(x: x(77.984), y: y(496)), control1: CGPoint(x: x(99.363), y: y(461.521)), control2: CGPoint(x: x(88.248), y: y(478.353)))
        p.addCurve(to: CGPoint(x: x(76.589), y: y(498.393)), control1: CGPoint(x: x(77.524), y: y(496.79)), control2: CGPoint(x: x(77.064), y: y(497.579)))
        p.addCurve(to: CGPoint(x: x(41.984), y: y(574)), control1: CGPoint(x: x(62.88), y: y(522.114)), control2: CGPoint(x: x(50.667), y: y(547.951)))
        p.addCurve(to: CGPoint(x: x(36.382), y: y(569.047)), control1: CGPoint(x: x(38.447), y: y(572.821)), control2: CGPoint(x: x(38.191), y: y(572.166)))
        p.addCurve(to: CGPoint(x: x(34.911), y: y(566.515)), control1: CGPoint(x: x(35.897), y: y(568.211)), control2: CGPoint(x: x(35.411), y: y(567.376)))
        p.addCurve(to: CGPoint(x: x(33.359), y: y(563.75)), control1: CGPoint(x: x(34.399), y: y(565.602)), control2: CGPoint(x: x(33.887), y: y(564.69)))
        p.addCurve(to: CGPoint(x: x(31.745), y: y(560.882)), control1: CGPoint(x: x(32.826), y: y(562.803)), control2: CGPoint(x: x(32.294), y: y(561.857)))
        p.addCurve(to: CGPoint(x: x(9.984), y: y(505)), control1: CGPoint(x: x(21.924), y: y(543.097)), control2: CGPoint(x: x(15.448), y: y(524.526)))
        p.addCurve(to: CGPoint(x: x(9.3), y: y(502.563)), control1: CGPoint(x: x(9.646), y: y(503.794)), control2: CGPoint(x: x(9.646), y: y(503.794)))
        p.addCurve(to: CGPoint(x: x(2.984), y: y(465)), control1: CGPoint(x: x(5.933), y: y(490.258)), control2: CGPoint(x: x(4.336), y: y(477.667)))
        p.addCurve(to: CGPoint(x: x(2.692), y: y(462.345)), control1: CGPoint(x: x(2.888), y: y(464.124)), control2: CGPoint(x: x(2.791), y: y(463.248)))
        p.addCurve(to: CGPoint(x: x(5.984), y: y(379)), control1: CGPoint(x: x(0), y: y(434.71)), control2: CGPoint(x: x(1.16), y: y(406.306)))
        p.addCurve(to: CGPoint(x: x(6.535), y: y(375.816)), control1: CGPoint(x: x(6.257), y: y(377.424)), control2: CGPoint(x: x(6.257), y: y(377.424)))
        p.addCurve(to: CGPoint(x: x(26.984), y: y(307)), control1: CGPoint(x: x(10.732), y: y(352.265)), control2: CGPoint(x: x(17.172), y: y(328.845)))
        p.addCurve(to: CGPoint(x: x(28.296), y: y(304.077)), control1: CGPoint(x: x(27.417), y: y(306.035)), control2: CGPoint(x: x(27.85), y: y(305.071)))
        p.addCurve(to: CGPoint(x: x(62.561), y: y(245.809)), control1: CGPoint(x: x(37.701), y: y(283.371)), control2: CGPoint(x: x(48.415), y: y(263.669)))
        p.addCurve(to: CGPoint(x: x(66.652), y: y(240.469)), control1: CGPoint(x: x(63.948), y: y(244.045)), control2: CGPoint(x: x(65.304), y: y(242.262)))
        p.addCurve(to: CGPoint(x: x(89.984), y: y(214)), control1: CGPoint(x: x(73.797), y: y(231.044)), control2: CGPoint(x: x(81.761), y: y(222.488)))
        p.addCurve(to: CGPoint(x: x(91.647), y: y(212.271)), control1: CGPoint(x: x(90.533), y: y(213.429)), control2: CGPoint(x: x(91.081), y: y(212.859)))
        p.addCurve(to: CGPoint(x: x(110.429), y: y(195.062)), control1: CGPoint(x: x(97.587), y: y(206.11)), control2: CGPoint(x: x(103.714), y: y(200.389)))
        p.addCurve(to: CGPoint(x: x(117.047), y: y(189.25)), control1: CGPoint(x: x(112.731), y: y(193.204)), control2: CGPoint(x: x(114.885), y: y(191.268)))
        p.addCurve(to: CGPoint(x: x(175.939), y: y(150.103)), control1: CGPoint(x: x(134.453), y: y(173.664)), control2: CGPoint(x: x(155.41), y: y(161.092)))
        p.addCurve(to: CGPoint(x: x(181.562), y: y(146.992)), control1: CGPoint(x: x(177.825), y: y(149.086)), control2: CGPoint(x: x(179.695), y: y(148.045)))
        p.addCurve(to: CGPoint(x: x(299.508), y: y(102.228)), control1: CGPoint(x: x(217.717), y: y(126.969)), control2: CGPoint(x: x(259.679), y: y(112.867)))
        p.addCurve(to: CGPoint(x: x(418.878), y: y(57.088)), control1: CGPoint(x: x(340.116), y: y(91.236)), control2: CGPoint(x: x(381.549), y: y(76.504)))
        p.addCurve(to: CGPoint(x: x(425.929), y: y(53.516)), control1: CGPoint(x: x(421.219), y: y(55.879)), control2: CGPoint(x: x(423.574), y: y(54.697)))
        p.addCurve(to: CGPoint(x: x(473.984), y: y(23)), control1: CGPoint(x: x(442.969), y: y(44.883)), control2: CGPoint(x: x(458.965), y: y(34.815)))
        p.addCurve(to: CGPoint(x: x(476.472), y: y(21.094)), control1: CGPoint(x: x(474.805), y: y(22.371)), control2: CGPoint(x: x(475.626), y: y(21.742)))
        p.addCurve(to: CGPoint(x: x(499.984), y: y(0)), control1: CGPoint(x: x(484.761), y: y(14.643)), control2: CGPoint(x: x(492.633), y: y(7.505)))
        p.closeSubpath()
        return p
    }
}

/// Backwards-compatible alias retained for callers that still reach for
/// the old shape names.
public typealias ReleafLeafShape = ReleafLeafBody

public struct ReleafLogo: View {
    public let size: CGFloat
    public let filled: Bool
    public let outlineColor: Color
    public let fillGradientStart: Color
    public let fillGradientEnd: Color
    public let lineWidth: CGFloat

    public init(
        size: CGFloat = 64,
        filled: Bool = true,
        outlineColor: Color = AppColors.onAccent,
        fillGradientStart: Color = AppColors.themeGreenPrimary,
        fillGradientEnd: Color = AppColors.themeGreenDeep,
        lineWidth: CGFloat = 2
    ) {
        self.size = size
        self.filled = filled
        self.outlineColor = outlineColor
        self.fillGradientStart = fillGradientStart
        self.fillGradientEnd = fillGradientEnd
        self.lineWidth = lineWidth
    }

    public var body: some View {
        Group {
            if filled {
                ReleafLeafBody()
                    .fill(
                        LinearGradient(
                            colors: [fillGradientStart, fillGradientEnd],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
            } else {
                ReleafLeafBody()
                    .stroke(
                        outlineColor,
                        style: StrokeStyle(
                            lineWidth: lineWidth,
                            lineCap: .round,
                            lineJoin: .round
                        )
                    )
            }
        }
        .frame(width: size, height: size)
    }
}

/// Solid-color filled leaf — used in app-icon and splash contexts where
/// the leaf is a single cream silhouette on a deep-green plate.
public struct ReleafLogoSolid: View {
    public let size: CGFloat
    public let leafColor: Color
    public let veinColor: Color
    public let veinWidth: CGFloat

    public init(
        size: CGFloat = 96,
        leafColor: Color = AppColors.onAccent,
        veinColor: Color = AppColors.themeGreenDeep,
        veinWidth: CGFloat = 3
    ) {
        self.size = size
        self.leafColor = leafColor
        self.veinColor = veinColor
        self.veinWidth = veinWidth
    }

    public var body: some View {
        ReleafLeafBody()
            .fill(leafColor)
            .frame(width: size, height: size)
    }
}

/// Outline-only variant used where the leaf sits on a colored plate
/// (splash, onboarding hero). The body fill is skipped so the plate
/// color shows through the leaf.
public struct ReleafLogoOutline: View {
    public let size: CGFloat
    public let color: Color
    public let lineWidth: CGFloat

    public init(size: CGFloat = 96, color: Color = AppColors.onAccent, lineWidth: CGFloat = 3) {
        self.size = size
        self.color = color
        self.lineWidth = lineWidth
    }

    public var body: some View {
        ReleafLogo(
            size: size,
            filled: false,
            outlineColor: color,
            lineWidth: lineWidth
        )
    }
}

#Preview {
    VStack(spacing: 32) {
        ReleafLogo(size: 120)
        ZStack {
            AppColors.themeGreenDeep.ignoresSafeArea()
            ReleafLogoSolid(size: 120, leafColor: AppColors.onAccent)
        }
        .frame(height: 200)
    }
    .padding()
    .background(AppColors.canvas)
}
