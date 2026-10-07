package io.noeriva.query;

import java.util.List;

public record HeatmapMatrix(int size, List<MatrixCell> cells) {}
