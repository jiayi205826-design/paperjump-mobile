package com.paperjump.mobile;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class LocalMatcher {
    static final class NodeMatch {
        final BindingData.Node node;
        final Point[] corners;

        NodeMatch(BindingData.Node node, Point[] corners) {
            this.node = node;
            this.corners = corners;
        }
    }

    static final class Match {
        final BindingData.Anchor anchor;
        final Point[] corners;
        final int inliers;
        final double inlierRatio;
        final List<NodeMatch> nodes;

        Match(BindingData.Anchor anchor, Point[] corners, int inliers, double inlierRatio,
              List<NodeMatch> nodes) {
            this.anchor = anchor;
            this.corners = corners;
            this.inliers = inliers;
            this.inlierRatio = inlierRatio;
            this.nodes = nodes;
        }
    }

    private final ORB localOrb = ORB.create(1200, 1.2f, 8, 8, 0, 2, ORB.HARRIS_SCORE, 15, 10);
    private final ORB naturalOrb = ORB.create(2500);
    private final BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);

    Match find(Mat gray, List<BindingData.Anchor> anchors) {
        Match fastLocal = findGroup(gray, anchors, false, true, 12);
        if (strong(fastLocal)) return fastLocal;
        Match fastNatural = findGroup(gray, anchors, true, true, 12);
        if (strong(fastNatural)) return fastNatural;
        Match local = findGroup(gray, anchors, false, false, 4);
        return local != null ? local : findGroup(gray, anchors, true, false, 4);
    }

    private static boolean strong(Match match) {
        return match != null && match.inliers >= 30 && match.inlierRatio >= .65;
    }

    private Match findGroup(Mat gray, List<BindingData.Anchor> anchors, boolean natural,
                            boolean fast, int minMargin) {
        MatOfKeyPoint queryPoints = new MatOfKeyPoint();
        Mat queryDescriptors = new Mat();
        try {
            Mat emptyMask = new Mat();
            try { (natural ? naturalOrb : localOrb).detectAndCompute(gray, emptyMask, queryPoints, queryDescriptors); }
            finally { emptyMask.release(); }
            if (queryDescriptors.empty()) return null;
            KeyPoint[] query = queryPoints.toArray();
            Match best = null, second = null;
            for (BindingData.Anchor anchor : anchors) {
                if (anchor.natural != natural) continue;
                Match current = match(anchor, query, queryDescriptors, fast);
                if (current == null) continue;
                if (best == null || current.inliers > best.inliers) {
                    second = best;
                    best = current;
                } else if (second == null || current.inliers > second.inliers) {
                    second = current;
                }
            }
            if (best != null && second != null
                    && !best.anchor.pageId.equals(second.anchor.pageId)
                    && best.inliers - second.inliers < minMargin) return null;
            return best;
        } finally {
            queryPoints.release();
            queryDescriptors.release();
        }
    }

    private Match match(BindingData.Anchor anchor, KeyPoint[] query, Mat descriptors,
                        boolean fast) {
        List<MatOfDMatch> pairs = new ArrayList<>();
        try {
            matcher.knnMatch(fast ? anchor.fastDescriptors : anchor.descriptors,
                    descriptors, pairs, 2);
            List<DMatch> good = new ArrayList<>();
            for (MatOfDMatch pair : pairs) {
                DMatch[] candidates = pair.toArray();
                if (candidates.length >= 2 && candidates[0].distance < .75 * candidates[1].distance)
                    good.add(candidates[0]);
            }
            if (good.size() < 8) return null;
            Point[] source = new Point[good.size()];
            Point[] target = new Point[good.size()];
            for (int i = 0; i < good.size(); i++) {
                DMatch hit = good.get(i);
                source[i] = (fast ? anchor.fastPoints : anchor.points)[hit.queryIdx];
                target[i] = query[hit.trainIdx].pt;
            }
            MatOfPoint2f src = new MatOfPoint2f(source);
            MatOfPoint2f dst = new MatOfPoint2f(target);
            Mat mask = new Mat();
            Mat h = null;
            try {
                h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3, mask);
                if (h.empty()) return null;
                List<Point> inlierSource = new ArrayList<>();
                int inliers = 0;
                double error = 0;
                for (int i = 0; i < good.size(); i++) {
                    if (mask.get(i, 0)[0] < .5) continue;
                    inliers++;
                    inlierSource.add(source[i]);
                    Point moved = project(h, source[i]);
                    error += Math.pow(moved.x - target[i].x, 2) + Math.pow(moved.y - target[i].y, 2);
                }
                if (inliers < 8 || inliers < good.size() * .5
                        || Math.sqrt(error / inliers) > 3) return null;
                double coverage = area(hull(inlierSource)) / (anchor.width * anchor.height);
                if (coverage < (anchor.natural ? .035 : .06)) return null;
                Point[] corners = new Point[]{
                        project(h, new Point(0, 0)),
                        project(h, new Point(anchor.width - 1, 0)),
                        project(h, new Point(anchor.width - 1, anchor.height - 1)),
                        project(h, new Point(0, anchor.height - 1))};
                if (!convex(corners)) return null;
                double ratio = area(Arrays.asList(corners)) / (anchor.width * anchor.height);
                if (ratio < .3 || ratio > 30) return null;
                List<NodeMatch> nodes = new ArrayList<>();
                for (BindingData.Node node : anchor.nodes) {
                    double[] r = node.region;
                    nodes.add(new NodeMatch(node, new Point[]{
                            project(h, new Point(r[0], r[1])),
                            project(h, new Point(r[2], r[1])),
                            project(h, new Point(r[2], r[3])),
                            project(h, new Point(r[0], r[3]))}));
                }
                return new Match(anchor, corners, inliers, inliers / (double) good.size(), nodes);
            } finally {
                src.release(); dst.release(); mask.release(); if (h != null) h.release();
            }
        } finally {
            for (MatOfDMatch pair : pairs) pair.release();
        }
    }

    private static Point project(Mat h, Point p) {
        double d = h.get(2, 0)[0] * p.x + h.get(2, 1)[0] * p.y + h.get(2, 2)[0];
        return new Point((h.get(0, 0)[0] * p.x + h.get(0, 1)[0] * p.y + h.get(0, 2)[0]) / d,
                (h.get(1, 0)[0] * p.x + h.get(1, 1)[0] * p.y + h.get(1, 2)[0]) / d);
    }

    private static List<Point> hull(List<Point> points) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble((Point p) -> p.x).thenComparingDouble(p -> p.y));
        List<Point> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (Point p : sorted) {
            while (lower.size() > 1 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0)
                lower.remove(lower.size() - 1);
            lower.add(p);
        }
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Point p = sorted.get(i);
            while (upper.size() > 1 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0)
                upper.remove(upper.size() - 1);
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static double area(List<Point> points) {
        double twice = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            twice += a.x * b.y - b.x * a.y;
        }
        return Math.abs(twice) / 2;
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private static boolean convex(Point[] points) {
        double sign = 0;
        for (int i = 0; i < points.length; i++) {
            Point a = points[i], b = points[(i + 1) % points.length], c = points[(i + 2) % points.length];
            double next = cross(a, b, c);
            if (!Double.isFinite(next) || Math.abs(next) < 1) return false;
            if (sign == 0) sign = Math.signum(next);
            else if (Math.signum(next) != sign) return false;
        }
        return true;
    }
}
