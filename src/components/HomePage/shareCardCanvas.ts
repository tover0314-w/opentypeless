import type {
  MetricPart,
  ShareCardKind,
  ShareHeatCell,
  ShareMetric,
  ShareViewModel,
} from './shareInsightsModel'

export const SHARE_IMAGE_SCALE = 2.5
export const SHARE_CARD_WIDTH = 512
export const SHARE_DOMAIN = 'opentypeless.com'

export function shareCardSize(kind: ShareCardKind) {
  return { width: SHARE_CARD_WIDTH, height: kind === 'insights' ? 460.8 : 288 }
}

const FONT =
  "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
const COLOR = {
  text: '#111111',
  tertiary: '#7c7c7c',
  surface: 'rgba(255, 255, 255, 0.86)',
  borderThin: 'rgba(119, 119, 119, 0.12)',
  borderThick: 'rgba(119, 119, 119, 0.18)',
  accent: '#2abba7',
  heat: [
    'rgba(119, 119, 119, 0.08)',
    'rgba(42, 187, 167, 0.3)',
    'rgba(42, 187, 167, 0.55)',
    'rgba(42, 187, 167, 0.8)',
    '#1c8a7a',
  ],
}

const PADDING = 19.2
const TITLE_SIZE = 38.4
const TITLE_LINE = 43.2
const CONTENT_GAP = 12
const TILE_HEIGHT = 74.2
const GRID_GAP = 9.6
const HEAT_CELL = 11.2
const HEAT_GAP = 6.4
const HEAT_ROWS = 7
const ACTIVITY_PAD_X = 19.2
const ACTIVITY_PAD_Y = 12.8
const HEATMAP_WIDTH = 235.2
const HEATMAP_GRID_HEIGHT = HEAT_ROWS * HEAT_CELL + (HEAT_ROWS - 1) * HEAT_GAP
const ACTIVITY_HEIGHT = ACTIVITY_PAD_Y * 2 + HEATMAP_GRID_HEIGHT + HEAT_GAP + 8

type Ctx = CanvasRenderingContext2D

function font(weight: number, size: number) {
  return `${weight} ${size}px ${FONT}`
}

function roundRect(ctx: Ctx, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

function ellipsize(ctx: Ctx, text: string, maxWidth: number) {
  if (ctx.measureText(text).width <= maxWidth) return text
  let end = text.length
  while (end > 0 && ctx.measureText(`${text.slice(0, end)}…`).width > maxWidth) end -= 1
  return `${text.slice(0, end)}…`
}

function drawBackground(ctx: Ctx, width: number, height: number) {
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, width, height)
  const glows: Array<[number, number, number, string]> = [
    [width * 0.08, -height * 0.05, width * 0.75, 'rgba(42, 187, 167, 0.26)'],
    [width * 1.02, height * 1.02, width * 0.7, 'rgba(120, 214, 255, 0.22)'],
    [width * 0.92, height * 0.08, width * 0.35, 'rgba(42, 187, 167, 0.1)'],
  ]
  for (const [x, y, radius, color] of glows) {
    const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius)
    gradient.addColorStop(0, color)
    gradient.addColorStop(1, 'rgba(255, 255, 255, 0)')
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, width, height)
  }
}

function drawTitle(ctx: Ctx, title: string, width: number) {
  const available = width - PADDING * 2
  ctx.font = font(600, TITLE_SIZE)
  const measured = ctx.measureText(title).width
  const ratio = measured > available ? available / measured : 1
  const size = TITLE_SIZE * ratio
  const line = TITLE_LINE * ratio
  ctx.font = font(600, size)
  ctx.fillStyle = COLOR.text
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(title, width / 2, PADDING + line / 2)
  ctx.textAlign = 'left'
}

/** Draws value/unit runs on one shared baseline, like inline text. */
function drawParts(
  ctx: Ctx,
  parts: MetricPart[],
  x: number,
  lineTop: number,
  lineHeight: number,
  valueSize: number,
  unitSize: number,
) {
  const baseline = lineTop + lineHeight / 2 + valueSize * 0.36
  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = COLOR.text
  ctx.font = font(500, 16)
  const space = ctx.measureText(' ').width
  let cursor = x
  parts.forEach((part, index) => {
    if (index > 0) cursor += space
    ctx.font = font(600, valueSize)
    ctx.fillText(part.value, cursor, baseline)
    cursor += ctx.measureText(part.value).width
    if (part.unit) {
      cursor += space
      ctx.font = font(500, unitSize)
      ctx.fillText(part.unit, cursor, baseline)
      cursor += ctx.measureText(part.unit).width
    }
  })
}

function drawHighlights(ctx: Ctx, metrics: ShareMetric[], top: number, width: number) {
  const tileWidth = (width - PADDING * 2 - GRID_GAP) / 2
  metrics.forEach((metric, index) => {
    const x = PADDING + (index % 2) * (tileWidth + GRID_GAP)
    const y = top + Math.floor(index / 2) * (TILE_HEIGHT + GRID_GAP)
    roundRect(ctx, x, y, tileWidth, TILE_HEIGHT, 12.8)
    ctx.fillStyle = COLOR.surface
    ctx.fill()
    drawParts(ctx, metric.parts, x + 12.8, y + 12.8, 25.6, 24, 12.8)
    ctx.font = font(400, 11.2)
    ctx.fillStyle = COLOR.tertiary
    ctx.textBaseline = 'middle'
    ctx.fillText(
      ellipsize(ctx, metric.label, tileWidth - 25.6),
      x + 12.8,
      y + 12.8 + 25.6 + 9.6 + 6.4,
    )
  })
  return Math.ceil(metrics.length / 2) * (TILE_HEIGHT + GRID_GAP) - GRID_GAP
}

function drawNoDataCell(ctx: Ctx, x: number, y: number) {
  roundRect(ctx, x, y, HEAT_CELL, HEAT_CELL, 3.2)
  ctx.fillStyle = '#ffffff'
  ctx.fill()
  ctx.save()
  ctx.clip()
  ctx.strokeStyle = COLOR.borderThick
  ctx.lineWidth = 1.2
  for (let offset = -HEAT_CELL; offset < HEAT_CELL * 2; offset += 3.6) {
    ctx.beginPath()
    ctx.moveTo(x + offset, y + HEAT_CELL)
    ctx.lineTo(x + offset + HEAT_CELL, y)
    ctx.stroke()
  }
  ctx.restore()
}

function drawHeatCell(ctx: Ctx, cell: ShareHeatCell, x: number, y: number) {
  if (cell.variant === 'noData') {
    drawNoDataCell(ctx, x, y)
    return
  }
  if (cell.variant === 'blank') {
    roundRect(ctx, x + 0.5, y + 0.5, HEAT_CELL - 1, HEAT_CELL - 1, 3.2)
    ctx.fillStyle = '#ffffff'
    ctx.fill()
    ctx.strokeStyle = COLOR.borderThin
    ctx.lineWidth = 1
    ctx.stroke()
    return
  }
  roundRect(ctx, x, y, HEAT_CELL, HEAT_CELL, 3.2)
  ctx.fillStyle = COLOR.heat[cell.variant]
  ctx.fill()
}

function drawHeatmap(
  ctx: Ctx,
  activity: ShareViewModel['activity'],
  weekdays: string[],
  x: number,
  y: number,
) {
  ctx.font = font(400, 6.4)
  ctx.fillStyle = COLOR.tertiary
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  weekdays.forEach((label, row) => {
    ctx.fillText(label, x + 3.5, y + row * (HEAT_CELL + HEAT_GAP) + HEAT_CELL / 2)
  })
  ctx.textAlign = 'left'

  const gridX = x + 7 + HEAT_GAP
  activity.cells.forEach((cell, index) => {
    const column = Math.floor(index / HEAT_ROWS)
    const row = index % HEAT_ROWS
    drawHeatCell(
      ctx,
      cell,
      gridX + column * (HEAT_CELL + HEAT_GAP),
      y + row * (HEAT_CELL + HEAT_GAP),
    )
  })

  const labelsY = y + HEATMAP_GRID_HEIGHT + HEAT_GAP
  ctx.save()
  ctx.beginPath()
  ctx.rect(x + 12.8, labelsY, HEATMAP_WIDTH - 12.8, 8)
  ctx.clip()
  ctx.font = font(400, 6.4)
  ctx.fillStyle = COLOR.tertiary
  ctx.textBaseline = 'middle'
  for (const label of activity.monthLabels) {
    ctx.fillText(label.text, x + 12.8 + label.columnIndex * (HEAT_CELL + HEAT_GAP), labelsY + 4)
  }
  ctx.restore()
}

function drawActivity(
  ctx: Ctx,
  activity: ShareViewModel['activity'],
  weekdays: string[],
  top: number,
  width: number,
) {
  const blockWidth = width - PADDING * 2
  roundRect(ctx, PADDING + 0.4, top + 0.4, blockWidth - 0.8, ACTIVITY_HEIGHT - 0.8, 12.8)
  ctx.fillStyle = COLOR.surface
  ctx.fill()
  ctx.strokeStyle = COLOR.borderThick
  ctx.lineWidth = 0.8
  ctx.stroke()

  const innerX = PADDING + ACTIVITY_PAD_X
  const heatmapX = PADDING + blockWidth - ACTIVITY_PAD_X - HEATMAP_WIDTH
  const statsWidth = heatmapX - innerX - 12
  activity.stats.forEach((stat, index) => {
    const y = top + ACTIVITY_PAD_Y + index * (14 + 6.4 + 11.2 + 12.8)
    drawParts(ctx, stat.parts, innerX, y, 14, 19.2, 9.6)
    ctx.font = font(400, 9.6)
    ctx.fillStyle = COLOR.tertiary
    ctx.textBaseline = 'middle'
    ctx.fillText(ellipsize(ctx, stat.label, statsWidth), innerX, y + 14 + 6.4 + 5.6)
  })
  drawHeatmap(ctx, activity, weekdays, heatmapX, top + ACTIVITY_PAD_Y)
}

function drawBrandMark(ctx: Ctx, x: number, y: number, size: number) {
  ctx.save()
  ctx.translate(x, y)
  ctx.scale(size / 24, size / 24)
  ctx.fillStyle = COLOR.text
  roundRect(ctx, 8.5, 2.5, 7, 12, 3.5)
  ctx.fill()
  ctx.strokeStyle = COLOR.text
  ctx.lineWidth = 2
  ctx.lineCap = 'round'
  ctx.stroke(new Path2D('M5 11.5c0 3.9 3.1 6.5 7 6.5s7-2.6 7-6.5'))
  ctx.stroke(new Path2D('M12 18v3.5'))
  ctx.beginPath()
  ctx.arc(19.5, 4.5, 2.5, 0, Math.PI * 2)
  ctx.fillStyle = COLOR.accent
  ctx.fill()
  ctx.restore()
}

function drawFooter(ctx: Ctx, width: number, height: number) {
  const top = height - PADDING - 19.2
  drawBrandMark(ctx, PADDING - 1, top + 1.6, 16)
  ctx.font = font(600, 13)
  ctx.fillStyle = COLOR.text
  ctx.textBaseline = 'middle'
  ctx.fillText('OpenTypeless', PADDING + 18, top + 9.6)
  ctx.font = font(400, 11.2)
  ctx.fillStyle = COLOR.tertiary
  ctx.textAlign = 'right'
  ctx.textBaseline = 'alphabetic'
  ctx.fillText(SHARE_DOMAIN, width - PADDING, top + 19.2 - 2.4)
  ctx.textAlign = 'left'
}

export interface ShareCardRenderOptions {
  weekdays: string[]
  scale?: number
}

/** Renders one share card at `scale` device pixels per CSS pixel; the preview shows this canvas. */
export function renderShareCard(
  kind: ShareCardKind,
  model: ShareViewModel,
  { weekdays, scale = SHARE_IMAGE_SCALE }: ShareCardRenderOptions,
): HTMLCanvasElement {
  const { width, height } = shareCardSize(kind)
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(width * scale)
  canvas.height = Math.round(height * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas 2D is unavailable')
  ctx.scale(scale, scale)

  drawBackground(ctx, width, height)
  drawTitle(ctx, model.titles[kind], width)
  let top = PADDING + TITLE_LINE + CONTENT_GAP
  if (kind !== 'activity') top += drawHighlights(ctx, model.highlights, top, width)
  if (kind !== 'highlights') {
    if (kind === 'insights') top += 11.6
    drawActivity(ctx, model.activity, weekdays, top, width)
  }
  drawFooter(ctx, width, height)
  return canvas
}

export function canvasToPng(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new Error('Failed to encode the share image'))
        return
      }
      blob
        .arrayBuffer()
        .then((buffer) => resolve(new Uint8Array(buffer)))
        .catch(reject)
    }, 'image/png')
  })
}

/** Sunday-first narrow weekday names in the card language. */
export function narrowWeekdays(language: string) {
  const format = new Intl.DateTimeFormat(language, { weekday: 'narrow', timeZone: 'UTC' })
  return Array.from({ length: 7 }, (_, day) => format.format(new Date(Date.UTC(2023, 0, 1 + day))))
}
