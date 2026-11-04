export const ZIP32_LIMIT = 0xffffffff;
const ENTRY_COUNT_LIMIT = 0xffff;

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(data: Uint8Array, previous = 0): number {
  let crc = ~previous >>> 0;
  for (let i = 0; i < data.length; i += 1) crc = CRC_TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  return ~crc >>> 0;
}

export function dosDateTime(when: Date): { time: number; date: number } {
  const year = Math.max(1980, Math.min(2107, when.getFullYear()));
  return {
    time: (when.getHours() << 11) | (when.getMinutes() << 5) | Math.floor(when.getSeconds() / 2),
    date: ((year - 1980) << 9) | ((when.getMonth() + 1) << 5) | when.getDate()
  };
}

const encoder = new TextEncoder();

export type ZipEntryRecord = {
  name: Uint8Array;
  crc: number;
  size: number;
  offset: number;
  time: number;
  date: number;
};

export function needsZip64(entry: Pick<ZipEntryRecord, "size" | "offset">): boolean {
  return entry.size >= ZIP32_LIMIT || entry.offset >= ZIP32_LIMIT;
}

export function localHeader(name: Uint8Array, time: number, date: number): Uint8Array {
  const out = new Uint8Array(30 + name.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, 0x04034b50, true);
  view.setUint16(4, 45, true);
  view.setUint16(6, 0x0808, true);
  view.setUint16(8, 0, true);
  view.setUint16(10, time, true);
  view.setUint16(12, date, true);
  view.setUint16(26, name.length, true);
  out.set(name, 30);
  return out;
}

export function dataDescriptor(crc: number, size: number): Uint8Array {
  const wide = size >= ZIP32_LIMIT;
  const out = new Uint8Array(wide ? 24 : 16);
  const view = new DataView(out.buffer);
  view.setUint32(0, 0x08074b50, true);
  view.setUint32(4, crc, true);
  if (wide) {
    view.setBigUint64(8, BigInt(size), true);
    view.setBigUint64(16, BigInt(size), true);
  } else {
    view.setUint32(8, size, true);
    view.setUint32(12, size, true);
  }
  return out;
}

export function centralRecord(entry: ZipEntryRecord): Uint8Array {
  const wide = needsZip64(entry);
  const extraLength = wide ? 28 : 0;
  const out = new Uint8Array(46 + entry.name.length + extraLength);
  const view = new DataView(out.buffer);
  view.setUint32(0, 0x02014b50, true);
  view.setUint16(4, 45, true);
  view.setUint16(6, 45, true);
  view.setUint16(8, 0x0808, true);
  view.setUint16(10, 0, true);
  view.setUint16(12, entry.time, true);
  view.setUint16(14, entry.date, true);
  view.setUint32(16, entry.crc, true);
  view.setUint32(20, wide ? ZIP32_LIMIT : entry.size, true);
  view.setUint32(24, wide ? ZIP32_LIMIT : entry.size, true);
  view.setUint16(28, entry.name.length, true);
  view.setUint16(30, extraLength, true);
  view.setUint32(42, wide ? ZIP32_LIMIT : entry.offset, true);
  out.set(entry.name, 46);
  if (wide) {
    const at = 46 + entry.name.length;
    view.setUint16(at, 0x0001, true);
    view.setUint16(at + 2, 24, true);
    view.setBigUint64(at + 4, BigInt(entry.size), true);
    view.setBigUint64(at + 12, BigInt(entry.size), true);
    view.setBigUint64(at + 20, BigInt(entry.offset), true);
  }
  return out;
}

export function endRecords(count: number, directorySize: number, directoryOffset: number): Uint8Array {
  const wide = count >= ENTRY_COUNT_LIMIT || directorySize >= ZIP32_LIMIT || directoryOffset >= ZIP32_LIMIT;
  const parts: Uint8Array[] = [];
  if (wide) {
    const record = new Uint8Array(56);
    const view = new DataView(record.buffer);
    view.setUint32(0, 0x06064b50, true);
    view.setBigUint64(4, BigInt(44), true);
    view.setUint16(12, 45, true);
    view.setUint16(14, 45, true);
    view.setBigUint64(24, BigInt(count), true);
    view.setBigUint64(32, BigInt(count), true);
    view.setBigUint64(40, BigInt(directorySize), true);
    view.setBigUint64(48, BigInt(directoryOffset), true);
    parts.push(record);
    const locator = new Uint8Array(20);
    const lv = new DataView(locator.buffer);
    lv.setUint32(0, 0x07064b50, true);
    lv.setBigUint64(8, BigInt(directoryOffset + directorySize), true);
    lv.setUint32(16, 1, true);
    parts.push(locator);
  }
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, wide ? ENTRY_COUNT_LIMIT : count, true);
  ev.setUint16(10, wide ? ENTRY_COUNT_LIMIT : count, true);
  ev.setUint32(12, wide ? ZIP32_LIMIT : directorySize, true);
  ev.setUint32(16, wide ? ZIP32_LIMIT : directoryOffset, true);
  parts.push(end);
  return concat(parts);
}

export function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

export type ZipSink = (chunk: Uint8Array) => Promise<void>;

export class ZipEntryWriter {
  private crc = 0;
  private size = 0;
  constructor(
    private readonly zip: ZipWriter,
    private readonly name: Uint8Array,
    private readonly offset: number,
    private readonly time: number,
    private readonly date: number
  ) {}

  get written(): number {
    return this.size;
  }

  async write(chunk: Uint8Array): Promise<void> {
    if (chunk.length === 0) return;
    this.crc = crc32(chunk, this.crc);
    this.size += chunk.length;
    await this.zip.emit(chunk);
  }

  async close(): Promise<void> {
    await this.zip.emit(dataDescriptor(this.crc, this.size));
    this.zip.record({ name: this.name, crc: this.crc, size: this.size, offset: this.offset, time: this.time, date: this.date });
    this.zip.release();
  }

  async discard(): Promise<void> {
    await this.zip.emit(dataDescriptor(this.crc, this.size));
    this.zip.release();
  }
}

export class ZipWriter {
  private offset = 0;
  private readonly entries: ZipEntryRecord[] = [];
  private open = false;
  private finished = false;

  constructor(private readonly sink: ZipSink, private readonly now: () => Date = () => new Date()) {}

  get bytesWritten(): number {
    return this.offset;
  }

  get count(): number {
    return this.entries.length;
  }

  async emit(chunk: Uint8Array): Promise<void> {
    await this.sink(chunk);
    this.offset += chunk.length;
  }

  record(entry: ZipEntryRecord): void {
    this.entries.push(entry);
  }

  release(): void {
    this.open = false;
  }

  async begin(path: string, modified?: Date): Promise<ZipEntryWriter> {
    if (this.open) throw new Error("Another entry is still being written.");
    if (this.finished) throw new Error("The archive is already closed.");
    this.open = true;
    const name = encoder.encode(path.replace(/\\/g, "/").replace(/^\/+/, ""));
    const { time, date } = dosDateTime(modified ?? this.now());
    const offset = this.offset;
    await this.emit(localHeader(name, time, date));
    return new ZipEntryWriter(this, name, offset, time, date);
  }

  async addBytes(path: string, data: Uint8Array | string, modified?: Date): Promise<void> {
    const entry = await this.begin(path, modified);
    await entry.write(typeof data === "string" ? encoder.encode(data) : data);
    await entry.close();
  }

  async finish(): Promise<void> {
    if (this.open) throw new Error("An entry is still being written.");
    if (this.finished) return;
    this.finished = true;
    const start = this.offset;
    for (const entry of this.entries) await this.emit(centralRecord(entry));
    await this.emit(endRecords(this.entries.length, this.offset - start, start));
  }
}
