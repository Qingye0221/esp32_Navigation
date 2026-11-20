#pragma once
// 地图模式判断、抗锯齿线条和多边形。由 MotoNav.ino 按依赖顺序包含，仅编译一次。
// 现在是否显示小地图
bool mapModeActive() {
  if (!mapOn || !inNav) return false;
  bool vecOK = mapValid && mapFrame == posFrame;
  bool rasOK = rasterValid && rasterFrame == posFrame;      // v2.5
  // v4.0 停车或暂停时手机可能不再发位置：不再 5 秒后切回箭头画面，保持地图（位置停在最后一次）
  if (!(styleIdx() == ST_DRAGON || vecOK || rasOK) || posAt == 0) return false;
  return true;                                      // v2.1 一直显示地图，路口信息放大显示在圆牌里
}

// ---- 画图工具 ----

// 粗折线（圆角连接）；屏幕外的线段直接跳过
// v2.2：只在方向变化明显的拐点补圆（直线上的点不画圆，省很多时间）；caps = 两端也画圆头
struct VicePlane { float nx, ny, c, support; uint8_t angle; };

static inline void viceBlendPixel(uint16_t *row, int x, uint16_t color, float coverage) {
  int alpha = (int)(coverage * 256.0f + 0.5f);
  if (alpha <= 0) return;
  row[x] = alpha >= 256 ? color : blendF(row[x], color, alpha);
}

void __attribute__((optimize("O3"))) viceAAQuad(const float *px, const float *py, uint16_t color) {
  VicePlane plane[4];
  float cx = 0, cy = 0, ymin = py[0], ymax = py[0];
  for (int i = 0; i < 4; i++) { cx += px[i]; cy += py[i]; ymin = fminf(ymin, py[i]); ymax = fmaxf(ymax, py[i]); }
  cx *= 0.25f; cy *= 0.25f;
  for (int i = 0; i < 4; i++) {
    int j = (i + 1) & 3;
    float dx = px[j] - px[i], dy = py[j] - py[i], length = sqrtf(dx * dx + dy * dy);
    if (length < 0.001f) return;
    float nx = -dy / length, ny = dx / length;
    if ((cx - px[i]) * nx + (cy - py[i]) * ny < 0) { nx = -nx; ny = -ny; }
    plane[i] = {nx, ny, -nx * px[i] - ny * py[i], (fabsf(nx) + fabsf(ny)) * 0.5f, viceCoverageAngle(nx, ny)};
  }
  uint16_t *fb = gfx->getFramebuffer();
  int ya = max(0, (int)floorf(ymin - 1)), yb = min(LCD_HEIGHT - 1, (int)ceilf(ymax + 1));
  for (int y = ya; y <= yb; y++) {
    float sy = y + 0.5f, left = 0.5f, right = LCD_WIDTH - 0.5f;
    float solidLeft = left, solidRight = right;
    bool outside = false, noSolid = false;
    float q[4];
    for (int i = 0; i < 4; i++) {
      q[i] = plane[i].ny * sy + plane[i].c;
      float nx = plane[i].nx, support = plane[i].support;
      if (fabsf(nx) < 0.00001f) {
        if (q[i] <= -support) outside = true;
        if (q[i] < support) noSolid = true;
      } else if (nx > 0) {
        left = fmaxf(left, (-support - q[i]) / nx);
        solidLeft = fmaxf(solidLeft, (support - q[i]) / nx);
      } else {
        right = fminf(right, (-support - q[i]) / nx);
        solidRight = fminf(solidRight, (support - q[i]) / nx);
      }
    }
    if (outside || left > right) continue;
    int xa = max(0, (int)ceilf(left - 0.5f)), xb = min(LCD_WIDTH - 1, (int)floorf(right - 0.5f));
    int ia = max(xa, (int)ceilf(solidLeft - 0.5f)), ib = min(xb, (int)floorf(solidRight - 0.5f));
    if (noSolid || ia > ib) { ia = xb + 1; ib = xb; }
    uint16_t *row = fb + y * LCD_WIDTH;
    for (int x = xa; x <= xb;) {
      if (x == ia && ia <= ib) {
        gfx->drawFastHLine(x, y, ib - ia + 1, color);
        x = ib + 1; continue;
      }
      float sx = x + 0.5f;
      int alpha = 256;
      for (int i = 0; i < 4; i++) {
        float distance = plane[i].nx * sx + q[i];
        if (distance <= -plane[i].support) { alpha = 0; break; }
        if (distance < plane[i].support) {
          int index = max(0, min(256, (int)floorf(128.5f + distance * (256.0f / 1.5f))));
          alpha = min(alpha, (int)viceCoverage[plane[i].angle][index]);
        }
      }
      if (alpha) row[x] = alpha >= 256 ? color : blendF(row[x], color, alpha);
      x++;
    }
  }
}

void __attribute__((optimize("O3"))) viceAACircle(float cx, float cy, float radius, uint16_t color) {
  uint16_t *fb = gfx->getFramebuffer();
  int ya = max(0, (int)floorf(cy - radius - 1)), yb = min(LCD_HEIGHT - 1, (int)ceilf(cy + radius + 1));
  float outer = radius + 0.5f, inner = fmaxf(0, radius - 0.5f);
  for (int y = ya; y <= yb; y++) {
    float dy = y + 0.5f - cy;
    if (fabsf(dy) >= outer) continue;
    float extent = sqrtf(outer * outer - dy * dy);
    int xa = max(0, (int)ceilf(cx - extent - 0.5f)), xb = min(LCD_WIDTH - 1, (int)floorf(cx + extent - 0.5f));
    int ia = xb + 1, ib = xb;
    if (fabsf(dy) < inner) {
      float core = sqrtf(inner * inner - dy * dy);
      ia = max(xa, (int)ceilf(cx - core - 0.5f)); ib = min(xb, (int)floorf(cx + core - 0.5f));
    }
    uint16_t *row = fb + y * LCD_WIDTH;
    for (int x = xa; x <= xb;) {
      if (x == ia && ia <= ib) {
        gfx->drawFastHLine(x, y, ib - ia + 1, color);
        x = ib + 1; continue;
      }
      float dx = x + 0.5f - cx;
      viceBlendPixel(row, x, color, outer - sqrtf(dx * dx + dy * dy));
      x++;
    }
  }
}

void __attribute__((optimize("O3"))) mapPolyline(const float *xs, const float *ys, int n, float w, uint16_t c, bool caps) {
  bool vice = styleIdx() == 1;
  if (vice) initViceSampling();
  float r = w / 2;
  float pdx = 0, pdy = 0;
  bool havePrev = false;
  for (int i = 0; i + 1 < n; i++) {
    float x0 = xs[i], y0 = ys[i], x1 = xs[i + 1], y1 = ys[i + 1];
    if ((x0 < -w && x1 < -w) || (x0 > LCD_WIDTH + w && x1 > LCD_WIDTH + w) ||
        (y0 < -w && y1 < -w) || (y0 > LCD_HEIGHT + w && y1 > LCD_HEIGHT + w)) continue;
    float dx = x1 - x0, dy = y1 - y0;
    float len = sqrtf(dx * dx + dy * dy);
    if (len < 0.5f) continue;
    float ux = dx / len, uy = dy / len;
    float nx = -uy * r, ny = ux * r;
    if (vice) {
      float px[4] = {x0 + nx, x0 - nx, x1 - nx, x1 + nx};
      float py[4] = {y0 + ny, y0 - ny, y1 - ny, y1 + ny};
      viceAAQuad(px, py, c);
    } else {
      gfx->fillTriangle(x0 + nx, y0 + ny, x0 - nx, y0 - ny, x1 + nx, y1 + ny, c);
      gfx->fillTriangle(x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny, c);
    }
    if (r >= 1.5f) {
      if (havePrev ? (ux * pdx + uy * pdy < 0.97f) : caps) {
        if (vice) viceAACircle(x0, y0, r, c); else gfx->fillCircle(x0, y0, r, c);
      }
      if (caps && i + 2 == n) {
        if (vice) viceAACircle(x1, y1, r, c); else gfx->fillCircle(x1, y1, r, c);
      }
    }
    pdx = ux; pdy = uy; havePrev = true;
  }
}

// 实心多边形（扫描线，奇偶规则）
void mapFillPoly(const float *xs, const float *ys, int n, uint16_t c) {
  if (n < 3) return;
  float ymin = 1e9f, ymax = -1e9f, xmin = 1e9f, xmax = -1e9f;
  for (int i = 0; i < n; i++) {
    ymin = fminf(ymin, ys[i]); ymax = fmaxf(ymax, ys[i]);
    xmin = fminf(xmin, xs[i]); xmax = fmaxf(xmax, xs[i]);
  }
  if (ymax < 0 || ymin >= LCD_HEIGHT || xmax < 0 || xmin >= LCD_WIDTH) return;
  int y0 = max(0, (int)ceilf(ymin - 0.5f)), y1 = min(LCD_HEIGHT - 1, (int)floorf(ymax - 0.5f));
  static float xs_hit[256];
  for (int y = y0; y <= y1; y++) {
    float sy = y + 0.5f;
    int k = 0;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      float ya = ys[j], yb = ys[i];
      if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy)) {
        if (k < 256) xs_hit[k++] = xs[j] + (sy - ya) * (xs[i] - xs[j]) / (yb - ya);
      }
    }
    for (int a = 1; a < k; a++) {               // 插入排序（交点通常很少）
      float v = xs_hit[a]; int b = a - 1;
      while (b >= 0 && xs_hit[b] > v) { xs_hit[b + 1] = xs_hit[b]; b--; }
      xs_hit[b + 1] = v;
    }
    for (int a = 0; a + 1 < k; a += 2) {
      int xa = max(0, (int)ceilf(xs_hit[a] - 0.5f)), xb = min(LCD_WIDTH - 1, (int)floorf(xs_hit[a + 1] - 0.5f));
      if (xb >= xa) gfx->drawFastHLine(xa, y, xb - xa + 1, c);
    }
  }
}

// 车头箭头（圆润平滑风格，对齐 App 图标的圆角飞掠箭头，抗锯齿深色描边与白芯）
void drawPlayer(float cx, float cy) {
  uint16_t *fb = gfx->getFramebuffer();
  if (!fb) {
    const float T[4][2] = {{0, -13}, {-10, 10.5f}, {0, 5}, {10, 10.5f}};
    for (int pass = 0; pass < 2; pass++) {
      float k = pass == 0 ? 1.35f : 1.0f;
      uint16_t c = pass == 0 ? C_BLACK : C_WHITE;
      float yo = pass == 0 ? 1.0f : 0;
      gfx->fillTriangle(cx + T[0][0] * k, cy + T[0][1] * k + yo, cx + T[1][0] * k, cy + T[1][1] * k + yo, cx + T[2][0] * k, cy + T[2][1] * k + yo, c);
      gfx->fillTriangle(cx + T[0][0] * k, cy + T[0][1] * k + yo, cx + T[2][0] * k, cy + T[2][1] * k + yo, cx + T[3][0] * k, cy + T[3][1] * k + yo, c);
    }
    return;
  }

  // App 图标同款圆角飞掠箭头（SDF 圆角多边形渲染，圆润头、圆润翼角、平滑内凹）
  const float p0x = 0.0f, p0y = -11.5f;
  const float p1x = 9.5f, p1y = 8.5f;
  const float p2x = 0.0f, p2y = 3.8f;

  const float v1x = p1x - p0x, v1y = p1y - p0y;
  const float invL1_2 = 1.0f / (v1x * v1x + v1y * v1y);

  const float v2x = p2x - p1x, v2y = p2y - p1y;
  const float invL2_2 = 1.0f / (v2x * v2x + v2y * v2y);

  const int x0 = max(0, (int)floorf(cx - 15.0f));
  const int x1 = min(LCD_WIDTH - 1, (int)ceilf(cx + 15.0f));
  const int y0 = max(0, (int)floorf(cy - 16.0f));
  const int y1 = min(LCD_HEIGHT - 1, (int)ceilf(cy + 14.0f));

  // 描边深色 0x1082 (#140E1E，对齐 App 图标背景黑紫色) 与白色内芯
  const uint16_t c_outline = 0x1082;
  const uint16_t c_body = C_WHITE;

  for (int y = y0; y <= y1; y++) {
    float qy = y + 0.5f - cy;
    uint16_t *row = &fb[y * LCD_WIDTH];
    for (int x = x0; x <= x1; x++) {
      float qx = fabsf(x + 0.5f - cx);

      // 1. 点到外掠边缘线段 P0->P1
      float t1 = (qx * v1x + (qy - p0y) * v1y) * invL1_2;
      t1 = t1 < 0.0f ? 0.0f : (t1 > 1.0f ? 1.0f : t1);
      float ex1 = qx - (p0x + t1 * v1x);
      float ey1 = qy - (p0y + t1 * v1y);
      float d1_2 = ex1 * ex1 + ey1 * ey1;

      // 2. 点到尾部内凹线段 P1->P2
      float t2 = ((qx - p1x) * v2x + (qy - p1y) * v2y) * invL2_2;
      t2 = t2 < 0.0f ? 0.0f : (t2 > 1.0f ? 1.0f : t2);
      float ex2 = qx - (p1x + t2 * v2x);
      float ey2 = qy - (p1y + t2 * v2y);
      float d2_2 = ex2 * ex2 + ey2 * ey2;

      float min_d2 = d1_2 < d2_2 ? d1_2 : d2_2;
      float min_d = sqrtf(min_d2);

      // 是否在内部
      bool inside = (qx * v1y - (qy - p0y) * v1x <= 0.0f) && ((qx - p1x) * v2y - (qy - p1y) * v2x <= 0.0f);
      float d = inside ? -min_d : min_d;

      // 圆润化参数
      const float r_body = 0.5f;
      const float r_out = 2.4f;

      if (d > r_out + 0.5f) continue;

      if (d <= r_body - 0.5f) {
        row[x] = c_body;
      } else if (d < r_body + 0.5f) {
        int t = (int)((r_body + 0.5f - d) * 255.0f);
        row[x] = blend565(c_outline, c_body, t);
      } else if (d <= r_out - 0.5f) {
        row[x] = c_outline;
      } else {
        float a = (r_out + 0.5f - d);
        if (a > 0.05f) {
          int t = (int)(a * 255.0f);
          row[x] = blend565(row[x], c_outline, t);
        }
      }
    }
  }
}
