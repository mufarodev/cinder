package com.discord.loader;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UiOps {

  public static final int WIRE_VERSION = 1;

  public static final int SYNTHETIC_ID_BASE = 0x70000000;

  public static final int OP_CREATE = 1;
  public static final int OP_UPDATE = 2;
  public static final int OP_CHILDREN = 3;
  public static final int OP_DELETE = 4;
  public static final int OP_ROOT = 5;
  public static final int OP_FOCUS = 6;
  public static final int OP_SCROLL_TO = 7;
  public static final int OP_REQUEST_FRAME = 8;
  public static final int OP_MOTION = 9;
  public static final int OP_CHILD_EDITS = 10;

  public static final int TAG_NULL = 0;
  public static final int TAG_FALSE = 1;
  public static final int TAG_TRUE = 2;
  public static final int TAG_INT32 = 3;
  public static final int TAG_INT64 = 4;
  public static final int TAG_FLOAT64 = 5;
  public static final int TAG_STRING = 6;
  public static final int TAG_ARRAY = 7;
  public static final int TAG_PROP_MAP = 8;
  public static final int TAG_FLOAT4 = 9;
  public static final int TAG_STRING_MAP = 10;
  public static final int TAG_WORD_LIST = 11;
  public static final int TAG_WORD_LIST_DELTA = 12;

  private static final String[] OP_NAMES = {
    "",
    "create",
    "update",
    "children",
    "delete",
    "root",
    "focus",
    "scrollTo",
    "requestFrame",
    "motion",
    "childEdits",
  };

  private static final int MAX_COLLECTION_COUNT = 1_000_000;
  private static final int MAX_DEPTH = 64;

  private static volatile String[] propNames = new String[0];
  private static volatile Map<String, Integer> propNameToId = Collections.emptyMap();

  public static void registerPropNames(String[] table) {
    if (table == null || table.length == 0) return;
    String[] copy = Arrays.copyOf(table, table.length);
    Map<String, Integer> rev = new HashMap<String, Integer>(copy.length * 2);
    for (int i = 0; i < copy.length; i++) {
      String s = copy[i];
      if (s != null && s.length() > 0 && !rev.containsKey(s)) {
        rev.put(s, Integer.valueOf(i));
      }
    }
    propNames = copy;
    propNameToId = Collections.unmodifiableMap(rev);
  }

  public static String propName(int propId) {
    String[] arr = propNames;
    if (propId < 0 || propId >= arr.length) return null;
    return arr[propId];
  }

  public static int propId(String name) {
    if (name == null) return -1;
    Integer id = propNameToId.get(name);
    return id != null ? id.intValue() : -1;
  }

  public static int propCount() {
    return propNames.length;
  }

  static void resetPropNamesForTests() {
    propNames = new String[0];
    propNameToId = Collections.emptyMap();
  }

  public static boolean isSyntheticId(int viewId) {
    return viewId >= SYNTHETIC_ID_BASE;
  }

  public static String opName(int opcode) {
    if (opcode >= 1 && opcode < OP_NAMES.length) {
      return OP_NAMES[opcode];
    }
    return "op#" + opcode;
  }

  private final List<Op> ops;

  public UiOps() {
    this.ops = new ArrayList<Op>();
  }

  public UiOps(List<Op> ops) {
    this.ops = new ArrayList<Op>(ops != null ? ops : Collections.<Op>emptyList());
  }

  public static UiOps create() {
    return new UiOps();
  }

  public static UiOps parse(byte[] data) {
    return data != null ? parse(data, data.length) : null;
  }

  public static UiOps parse(byte[] data, int length) {
    if (data == null || length < 1 || length > data.length) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
    int version = buf.get() & 0xff;
    if (version != WIRE_VERSION) {
      return null;
    }
    List<Op> parsed = new ArrayList<Op>();
    try {
      while (buf.hasRemaining()) {
        int opcode = buf.get() & 0xff;
        Op op = readOp(buf, opcode);
        if (op == null) return null;
        parsed.add(op);
      }
    } catch (RuntimeException e) {
      return null;
    }
    return new UiOps(parsed);
  }

  public List<Op> ops() {
    return ops;
  }

  public int size() {
    return ops.size();
  }

  public boolean isEmpty() {
    return ops.isEmpty();
  }

  public Op get(int index) {
    return ops.get(index);
  }

  public UiOps add(Op op) {
    if (op == null) throw new IllegalArgumentException("op == null");
    ops.add(op);
    return this;
  }

  public UiOps addAll(Collection<Op> more) {
    if (more != null) {
      for (Op op : more) {
        add(op);
      }
    }
    return this;
  }

  public UiOps createView(int viewId, String viewType, List<Prop> props) {
    return add(Op.create(viewId, viewType, props));
  }

  public UiOps updateView(int viewId, List<Prop> props) {
    return add(Op.update(viewId, props));
  }

  public UiOps setChildren(int parentId, int... childIds) {
    return add(Op.children(parentId, childIds));
  }

  public UiOps deleteViews(int... viewIds) {
    return add(Op.delete(viewIds));
  }

  public UiOps setRoot(int viewId) {
    return add(Op.root(viewId));
  }

  public UiOps focus(int viewId) {
    return add(Op.focus(viewId));
  }

  public UiOps clearFocus() {
    return add(Op.clearFocus());
  }

  public UiOps scrollTo(int viewId, Value options) {
    return add(Op.scrollTo(viewId, options));
  }

  public UiOps requestFrame() {
    return add(Op.requestFrame());
  }

  public UiOps motion(int... words) {
    return add(Op.motion(words));
  }

  public UiOps childEdits(int parentId, List<ChildEdit> edits) {
    return add(Op.childEdits(parentId, edits));
  }

  public byte[] toBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, ops.size() * 24));
    out.write(WIRE_VERSION);
    byte[] scratch = new byte[8];
    for (int i = 0; i < ops.size(); i++) {
      writeOp(out, scratch, ops.get(i));
    }
    return out.toByteArray();
  }

  private static Op readOp(ByteBuffer buf, int opcode) {
    switch (opcode) {
      case OP_CREATE:
        {
          if (buf.remaining() < 6) return null;
          int viewId = buf.getInt();
          int typeLen = buf.getShort() & 0xffff;
          String viewType = readUtf8(buf, typeLen);
          if (viewType == null) return null;
          List<Prop> props = readPropList(buf, 0);
          if (props == null) return null;
          return Op.create(viewId, viewType, props);
        }
      case OP_UPDATE:
        {
          if (buf.remaining() < 6) return null;
          int viewId = buf.getInt();
          List<Prop> props = readPropList(buf, 0);
          if (props == null) return null;
          return Op.update(viewId, props);
        }
      case OP_CHILDREN:
        {
          if (buf.remaining() < 8) return null;
          int parentId = buf.getInt();
          int count = buf.getInt();
          int[] ids = readIntArray(buf, count);
          if (ids == null) return null;
          return Op.children(parentId, ids);
        }
      case OP_DELETE:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          int[] ids = readIntArray(buf, count);
          if (ids == null) return null;
          return Op.delete(ids);
        }
      case OP_ROOT:
        {
          if (buf.remaining() < 4) return null;
          return Op.root(buf.getInt());
        }
      case OP_FOCUS:
        {
          if (buf.remaining() < 1) return null;
          int hasTarget = buf.get() & 0xff;
          if (hasTarget == 0) {
            return Op.clearFocus();
          }
          if (buf.remaining() < 4) return null;
          return Op.focus(buf.getInt());
        }
      case OP_SCROLL_TO:
        {
          if (buf.remaining() < 5) return null;
          int viewId = buf.getInt();
          Value options = readValue(buf, 0);
          if (options == null) return null;
          return Op.scrollTo(viewId, options);
        }
      case OP_REQUEST_FRAME:
        return Op.requestFrame();
      case OP_MOTION:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          int[] words = readIntArray(buf, count);
          if (words == null) return null;
          return Op.motion(words);
        }
      case OP_CHILD_EDITS:
        {
          if (buf.remaining() < 8) return null;
          int parentId = buf.getInt();
          int editCount = buf.getInt();
          if (editCount < 0
              || editCount > MAX_COLLECTION_COUNT
              || buf.remaining() < editCount * 12L) {
            return null;
          }
          List<ChildEdit> edits = new ArrayList<ChildEdit>(editCount);
          for (int i = 0; i < editCount; i++) {
            if (buf.remaining() < 12) return null;
            int keep = buf.getInt();
            int del = buf.getInt();
            int ins = buf.getInt();
            if (keep < 0 || del < 0) return null;
            int[] insertIds = readIntArray(buf, ins);
            if (insertIds == null) return null;
            edits.add(new ChildEdit(keep, del, insertIds));
          }
          return Op.childEdits(parentId, edits);
        }
      default:
        return null;
    }
  }

  private static List<Prop> readPropList(ByteBuffer buf, int depth) {
    if (buf.remaining() < 2) return null;
    int count = buf.getShort() & 0xffff;
    List<Prop> props = new ArrayList<Prop>(count);
    for (int i = 0; i < count; i++) {
      if (buf.remaining() < 3) return null;
      int propId = buf.getShort() & 0xffff;
      Value val = readValue(buf, depth + 1);
      if (val == null) return null;
      props.add(new Prop(propId, val));
    }
    return props;
  }

  static Value readValue(ByteBuffer buf, int depth) {
    if (depth > MAX_DEPTH || !buf.hasRemaining()) return null;
    int tag = buf.get() & 0xff;
    switch (tag) {
      case TAG_NULL:
        return Value.ofNull();
      case TAG_FALSE:
        return Value.ofBool(false);
      case TAG_TRUE:
        return Value.ofBool(true);
      case TAG_INT32:
        if (buf.remaining() < 4) return null;
        return Value.ofInt(buf.getInt());
      case TAG_INT64:
        if (buf.remaining() < 8) return null;
        return Value.ofLong(buf.getLong());
      case TAG_FLOAT64:
        if (buf.remaining() < 8) return null;
        return Value.ofDouble(buf.getDouble());
      case TAG_STRING:
        {
          if (buf.remaining() < 4) return null;
          int len = buf.getInt();
          String s = readUtf8(buf, len);
          return s != null ? Value.ofString(s) : null;
        }
      case TAG_ARRAY:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          if (count < 0 || count > MAX_COLLECTION_COUNT || count > buf.remaining()) {
            return null;
          }
          List<Value> items = new ArrayList<Value>(count);
          for (int i = 0; i < count; i++) {
            Value v = readValue(buf, depth + 1);
            if (v == null) return null;
            items.add(v);
          }
          return Value.ofArray(items);
        }
      case TAG_PROP_MAP:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          if (count < 0 || count > MAX_COLLECTION_COUNT || buf.remaining() < count * 3L) {
            return null;
          }
          List<Prop> entries = new ArrayList<Prop>(count);
          for (int i = 0; i < count; i++) {
            if (buf.remaining() < 3) return null;
            int propId = buf.getShort() & 0xffff;
            Value v = readValue(buf, depth + 1);
            if (v == null) return null;
            entries.add(new Prop(propId, v));
          }
          return Value.ofPropMap(entries);
        }
      case TAG_FLOAT4:
        {
          if (buf.remaining() < 16) return null;
          float f0 = buf.getFloat();
          float f1 = buf.getFloat();
          float f2 = buf.getFloat();
          float f3 = buf.getFloat();
          return Value.ofFloat4(f0, f1, f2, f3);
        }
      case TAG_STRING_MAP:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          if (count < 0 || count > MAX_COLLECTION_COUNT || buf.remaining() < count * 5L) {
            return null;
          }
          List<NamedValue> entries = new ArrayList<NamedValue>(count);
          for (int i = 0; i < count; i++) {
            if (buf.remaining() < 4) return null;
            int keyLen = buf.getInt();
            String key = readUtf8(buf, keyLen);
            if (key == null) return null;
            Value v = readValue(buf, depth + 1);
            if (v == null) return null;
            entries.add(new NamedValue(key, v));
          }
          return Value.ofStringMap(entries);
        }
      case TAG_WORD_LIST:
        {
          if (buf.remaining() < 4) return null;
          int count = buf.getInt();
          int[] words = readIntArray(buf, count);
          return words != null ? Value.ofWordList(words) : null;
        }
      case TAG_WORD_LIST_DELTA:
        {
          if (buf.remaining() < 6) return null;
          int baseIndex = buf.getInt();
          int patchCount = buf.getShort() & 0xffff;
          if (buf.remaining() < patchCount * 6L) return null;
          int[] indices = new int[patchCount];
          int[] vals = new int[patchCount];
          for (int i = 0; i < patchCount; i++) {
            indices[i] = buf.getShort() & 0xffff;
            vals[i] = buf.getInt();
          }
          return Value.ofWordListDelta(baseIndex, indices, vals);
        }
      default:
        return null;
    }
  }

  private static int[] readIntArray(ByteBuffer buf, int count) {
    if (count < 0 || count > MAX_COLLECTION_COUNT || buf.remaining() < count * 4L) {
      return null;
    }
    int[] arr = new int[count];
    for (int i = 0; i < count; i++) {
      arr[i] = buf.getInt();
    }
    return arr;
  }

  private static String readUtf8(ByteBuffer buf, int byteLen) {
    if (byteLen < 0 || byteLen > buf.remaining()) {
      return null;
    }
    byte[] tmp = new byte[byteLen];
    buf.get(tmp);
    return new String(tmp, StandardCharsets.UTF_8);
  }

  private static void writeOp(ByteArrayOutputStream out, byte[] scratch, Op op) {
    out.write(op.opcode);
    switch (op.opcode) {
      case OP_CREATE:
        {
          writeIntLE(out, scratch, op.viewId);
          byte[] typeBytes = op.viewType.getBytes(StandardCharsets.UTF_8);
          if (typeBytes.length > 0xffff) {
            throw new IllegalArgumentException("viewType too long: " + typeBytes.length);
          }
          writeShortLE(out, scratch, typeBytes.length);
          out.write(typeBytes, 0, typeBytes.length);
          writePropList(out, scratch, op.props);
          break;
        }
      case OP_UPDATE:
        {
          writeIntLE(out, scratch, op.viewId);
          writePropList(out, scratch, op.props);
          break;
        }
      case OP_CHILDREN:
        {
          writeIntLE(out, scratch, op.viewId);
          writeIntArray(out, scratch, op.intData);
          break;
        }
      case OP_DELETE:
      case OP_MOTION:
        {
          writeIntArray(out, scratch, op.intData);
          break;
        }
      case OP_ROOT:
        {
          writeIntLE(out, scratch, op.viewId);
          break;
        }
      case OP_FOCUS:
        {
          if (op.hasFocusTarget) {
            out.write(1);
            writeIntLE(out, scratch, op.viewId);
          } else {
            out.write(0);
          }
          break;
        }
      case OP_SCROLL_TO:
        {
          writeIntLE(out, scratch, op.viewId);
          writeValue(
              out,
              scratch,
              op.scrollOptions != null
                  ? op.scrollOptions
                  : Value.ofPropMap(Collections.<Prop>emptyList()));
          break;
        }
      case OP_REQUEST_FRAME:
        break;
      case OP_CHILD_EDITS:
        {
          writeIntLE(out, scratch, op.viewId);
          int n = op.childEdits != null ? op.childEdits.size() : 0;
          writeIntLE(out, scratch, n);
          for (int i = 0; i < n; i++) {
            ChildEdit e = op.childEdits.get(i);
            writeIntLE(out, scratch, e.keepCount);
            writeIntLE(out, scratch, e.deleteCount);
            writeIntArray(out, scratch, e.insertIds);
          }
          break;
        }
      default:
        throw new IllegalArgumentException("unknown opcode: " + op.opcode);
    }
  }

  private static void writePropList(ByteArrayOutputStream out, byte[] scratch, List<Prop> props) {
    int n = props != null ? props.size() : 0;
    if (n > 0xffff) {
      throw new IllegalArgumentException("propCount > 65535: " + n);
    }
    writeShortLE(out, scratch, n);
    for (int i = 0; i < n; i++) {
      Prop p = props.get(i);
      writeShortLE(out, scratch, p.propId);
      writeValue(out, scratch, p.value);
    }
  }

  static void writeValue(ByteArrayOutputStream out, byte[] scratch, Value v) {
    if (v == null) {
      out.write(TAG_NULL);
      return;
    }
    out.write(v.tag);
    switch (v.tag) {
      case TAG_NULL:
      case TAG_FALSE:
      case TAG_TRUE:
        break;
      case TAG_INT32:
        writeIntLE(out, scratch, (int) v.numBits);
        break;
      case TAG_INT64:
      case TAG_FLOAT64:
        writeLongLE(out, scratch, v.numBits);
        break;
      case TAG_STRING:
        {
          byte[] utf8 = ((String) v.ref).getBytes(StandardCharsets.UTF_8);
          writeIntLE(out, scratch, utf8.length);
          out.write(utf8, 0, utf8.length);
          break;
        }
      case TAG_ARRAY:
        {
          @SuppressWarnings("unchecked")
          List<Value> items = (List<Value>) v.ref;
          writeIntLE(out, scratch, items.size());
          for (int i = 0; i < items.size(); i++) {
            writeValue(out, scratch, items.get(i));
          }
          break;
        }
      case TAG_PROP_MAP:
        {
          @SuppressWarnings("unchecked")
          List<Prop> entries = (List<Prop>) v.ref;
          writeIntLE(out, scratch, entries.size());
          for (int i = 0; i < entries.size(); i++) {
            Prop p = entries.get(i);
            writeShortLE(out, scratch, p.propId);
            writeValue(out, scratch, p.value);
          }
          break;
        }
      case TAG_FLOAT4:
        {
          float[] f = (float[]) v.ref;
          for (int i = 0; i < 4; i++) {
            writeIntLE(out, scratch, Float.floatToRawIntBits(f[i]));
          }
          break;
        }
      case TAG_STRING_MAP:
        {
          @SuppressWarnings("unchecked")
          List<NamedValue> entries = (List<NamedValue>) v.ref;
          writeIntLE(out, scratch, entries.size());
          for (int i = 0; i < entries.size(); i++) {
            NamedValue nv = entries.get(i);
            byte[] k = nv.key.getBytes(StandardCharsets.UTF_8);
            writeIntLE(out, scratch, k.length);
            out.write(k, 0, k.length);
            writeValue(out, scratch, nv.value);
          }
          break;
        }
      case TAG_WORD_LIST:
        {
          int[] words = (int[]) v.ref;
          writeIntArray(out, scratch, words);
          break;
        }
      case TAG_WORD_LIST_DELTA:
        {
          WordListDelta d = (WordListDelta) v.ref;
          writeIntLE(out, scratch, d.baseIndex);
          writeShortLE(out, scratch, d.slotIndices.length);
          for (int i = 0; i < d.slotIndices.length; i++) {
            writeShortLE(out, scratch, d.slotIndices[i]);
            writeIntLE(out, scratch, d.values[i]);
          }
          break;
        }
      default:
        throw new IllegalArgumentException("unknown value tag: " + v.tag);
    }
  }

  private static void writeIntArray(ByteArrayOutputStream out, byte[] scratch, int[] arr) {
    int n = arr != null ? arr.length : 0;
    writeIntLE(out, scratch, n);
    for (int i = 0; i < n; i++) {
      writeIntLE(out, scratch, arr[i]);
    }
  }

  static void writeShortLE(ByteArrayOutputStream out, byte[] s, int v) {
    s[0] = (byte) (v & 0xff);
    s[1] = (byte) ((v >>> 8) & 0xff);
    out.write(s, 0, 2);
  }

  static void writeIntLE(ByteArrayOutputStream out, byte[] s, int v) {
    s[0] = (byte) (v & 0xff);
    s[1] = (byte) ((v >>> 8) & 0xff);
    s[2] = (byte) ((v >>> 16) & 0xff);
    s[3] = (byte) ((v >>> 24) & 0xff);
    out.write(s, 0, 4);
  }

  static void writeLongLE(ByteArrayOutputStream out, byte[] s, long v) {
    s[0] = (byte) (v & 0xffL);
    s[1] = (byte) ((v >>> 8) & 0xffL);
    s[2] = (byte) ((v >>> 16) & 0xffL);
    s[3] = (byte) ((v >>> 24) & 0xffL);
    s[4] = (byte) ((v >>> 32) & 0xffL);
    s[5] = (byte) ((v >>> 40) & 0xffL);
    s[6] = (byte) ((v >>> 48) & 0xffL);
    s[7] = (byte) ((v >>> 56) & 0xffL);
    out.write(s, 0, 8);
  }

  public static final class Prop {
    public final int propId;
    public final Value value;

    public Prop(int propId, Value value) {
      if (propId < 0 || propId > 0xffff) {
        throw new IllegalArgumentException("invalid propId: " + propId);
      }
      this.propId = propId;
      this.value = value != null ? value : Value.ofNull();
    }

    public static Prop named(String propName, Value value) {
      int id = UiOps.propId(propName);
      if (id < 0) {
        throw new IllegalArgumentException("unknown prop name: " + propName);
      }
      return new Prop(id, value);
    }

    public String name() {
      String n = UiOps.propName(propId);
      return n != null ? n : ("p#" + propId);
    }

    public String rawNameOrNull() {
      return UiOps.propName(propId);
    }

    @Override
    public String toString() {
      return name() + "=" + value.toSummaryString();
    }
  }

  public static final class NamedValue {
    public final String key;
    public final Value value;

    public NamedValue(String key, Value value) {
      this.key = key != null ? key : "";
      this.value = value != null ? value : Value.ofNull();
    }
  }

  public static final class WordListDelta {
    public final int baseIndex;
    public final int[] slotIndices;
    public final int[] values;

    public WordListDelta(int baseIndex, int[] slotIndices, int[] values) {
      if (slotIndices == null || values == null || slotIndices.length != values.length) {
        throw new IllegalArgumentException("mismatched WordListDelta arrays");
      }
      this.baseIndex = baseIndex;
      this.slotIndices = Arrays.copyOf(slotIndices, slotIndices.length);
      this.values = Arrays.copyOf(values, values.length);
    }
  }

  public static final class ChildEdit {
    public final int keepCount;
    public final int deleteCount;
    public final int[] insertIds;

    public ChildEdit(int keepCount, int deleteCount, int... insertIds) {
      if (keepCount < 0 || deleteCount < 0) {
        throw new IllegalArgumentException("negative ChildEdit counts");
      }
      this.keepCount = keepCount;
      this.deleteCount = deleteCount;
      this.insertIds = insertIds != null ? Arrays.copyOf(insertIds, insertIds.length) : new int[0];
    }
  }

  public static final class Value {
    private static final Value NULL_VAL = new Value(TAG_NULL, 0L, null);
    private static final Value FALSE_VAL = new Value(TAG_FALSE, 0L, null);
    private static final Value TRUE_VAL = new Value(TAG_TRUE, 1L, null);

    final int tag;
    final long numBits;
    final Object ref;

    private Value(int tag, long numBits, Object ref) {
      this.tag = tag;
      this.numBits = numBits;
      this.ref = ref;
    }

    public static Value ofNull() {
      return NULL_VAL;
    }

    public static Value ofBool(boolean b) {
      return b ? TRUE_VAL : FALSE_VAL;
    }

    public static Value ofInt(int v) {
      return new Value(TAG_INT32, v, null);
    }

    public static Value ofLong(long v) {
      return new Value(TAG_INT64, v, null);
    }

    public static Value ofDouble(double v) {
      return new Value(TAG_FLOAT64, Double.doubleToRawLongBits(v), null);
    }

    public static Value ofString(String s) {
      if (s == null) return NULL_VAL;
      return new Value(TAG_STRING, 0L, s);
    }

    public static Value ofArray(List<Value> items) {
      return new Value(
          TAG_ARRAY,
          0L,
          new ArrayList<Value>(items != null ? items : Collections.<Value>emptyList()));
    }

    public static Value ofPropMap(List<Prop> entries) {
      return new Value(
          TAG_PROP_MAP,
          0L,
          new ArrayList<Prop>(entries != null ? entries : Collections.<Prop>emptyList()));
    }

    public static Value ofFloat4(float a, float b, float c, float d) {
      return new Value(TAG_FLOAT4, 0L, new float[] {a, b, c, d});
    }

    public static Value ofStringMap(List<NamedValue> entries) {
      return new Value(
          TAG_STRING_MAP,
          0L,
          new ArrayList<NamedValue>(
              entries != null ? entries : Collections.<NamedValue>emptyList()));
    }

    public static Value ofWordList(int... words) {
      return new Value(
          TAG_WORD_LIST, 0L, words != null ? Arrays.copyOf(words, words.length) : new int[0]);
    }

    public static Value ofWordListDelta(int baseIndex, int[] slotIndices, int[] values) {
      return new Value(TAG_WORD_LIST_DELTA, 0L, new WordListDelta(baseIndex, slotIndices, values));
    }

    public int tag() {
      return tag;
    }

    public boolean isNull() {
      return tag == TAG_NULL;
    }

    public boolean isBoolean() {
      return tag == TAG_FALSE || tag == TAG_TRUE;
    }

    public boolean asBoolean() {
      return tag == TAG_TRUE;
    }

    public int asInt() {
      return (int) numBits;
    }

    public long asLong() {
      return tag == TAG_INT32 ? (int) numBits : numBits;
    }

    public double asDouble() {
      if (tag == TAG_FLOAT64) return Double.longBitsToDouble(numBits);
      if (tag == TAG_INT32) return (int) numBits;
      if (tag == TAG_INT64) return numBits;
      return 0.0;
    }

    public String asString() {
      return tag == TAG_STRING ? (String) ref : null;
    }

    @SuppressWarnings("unchecked")
    public List<Value> asArray() {
      return tag == TAG_ARRAY ? (List<Value>) ref : null;
    }

    @SuppressWarnings("unchecked")
    public List<Prop> asPropMap() {
      return tag == TAG_PROP_MAP ? (List<Prop>) ref : null;
    }

    public float[] asFloat4() {
      return tag == TAG_FLOAT4 ? Arrays.copyOf((float[]) ref, 4) : null;
    }

    @SuppressWarnings("unchecked")
    public List<NamedValue> asStringMap() {
      return tag == TAG_STRING_MAP ? (List<NamedValue>) ref : null;
    }

    public int[] asWordList() {
      return tag == TAG_WORD_LIST ? Arrays.copyOf((int[]) ref, ((int[]) ref).length) : null;
    }

    public WordListDelta asWordListDelta() {
      return tag == TAG_WORD_LIST_DELTA ? (WordListDelta) ref : null;
    }

    public boolean containsString(String needle) {
      if (needle == null || needle.length() == 0) return false;
      switch (tag) {
        case TAG_STRING:
          return ((String) ref).contains(needle);
        case TAG_ARRAY:
          {
            List<Value> arr = asArray();
            for (int i = 0; i < arr.size(); i++) {
              if (arr.get(i).containsString(needle)) return true;
            }
            return false;
          }
        case TAG_PROP_MAP:
          {
            List<Prop> map = asPropMap();
            for (int i = 0; i < map.size(); i++) {
              if (map.get(i).value.containsString(needle)) return true;
            }
            return false;
          }
        case TAG_STRING_MAP:
          {
            List<NamedValue> map = asStringMap();
            for (int i = 0; i < map.size(); i++) {
              NamedValue nv = map.get(i);
              if (nv.key.contains(needle) || nv.value.containsString(needle)) return true;
            }
            return false;
          }
        default:
          return false;
      }
    }

    public String toSummaryString() {
      return toSummaryString(0);
    }

    private String toSummaryString(int depth) {
      switch (tag) {
        case TAG_NULL:
          return "null";
        case TAG_FALSE:
          return "false";
        case TAG_TRUE:
          return "true";
        case TAG_INT32:
          return Integer.toString((int) numBits);
        case TAG_INT64:
          return numBits + "L";
        case TAG_FLOAT64:
          {
            double d = Double.longBitsToDouble(numBits);
            if (d == (long) d && Math.abs(d) < 1e9) {
              return Long.toString((long) d);
            }
            return Double.toString(d);
          }
        case TAG_STRING:
          {
            String s = (String) ref;
            if (s.length() > 48) {
              return "\"" + s.substring(0, 45) + "...\"(" + s.length() + ")";
            }
            return "\"" + s + "\"";
          }
        case TAG_FLOAT4:
          {
            float[] f = (float[]) ref;
            return "[" + f[0] + "," + f[1] + "," + f[2] + "," + f[3] + "]";
          }
        case TAG_ARRAY:
          {
            List<Value> items = asArray();
            if (depth >= 2) return "[len=" + items.size() + "]";
            StringBuilder sb = new StringBuilder("[");
            int lim = Math.min(items.size(), 6);
            for (int i = 0; i < lim; i++) {
              if (i > 0) sb.append(',');
              sb.append(items.get(i).toSummaryString(depth + 1));
            }
            if (items.size() > lim) sb.append(",...").append(items.size());
            sb.append(']');
            return sb.toString();
          }
        case TAG_PROP_MAP:
          {
            List<Prop> entries = asPropMap();
            if (depth >= 2) return "{props=" + entries.size() + "}";
            StringBuilder sb = new StringBuilder("{");
            int lim = Math.min(entries.size(), 10);
            for (int i = 0; i < lim; i++) {
              if (i > 0) sb.append(',');
              Prop p = entries.get(i);
              sb.append(p.name()).append('=').append(p.value.toSummaryString(depth + 1));
            }
            if (entries.size() > lim) sb.append(",...").append(entries.size());
            sb.append('}');
            return sb.toString();
          }
        case TAG_STRING_MAP:
          {
            List<NamedValue> entries = asStringMap();
            if (depth >= 2) return "{keys=" + entries.size() + "}";
            StringBuilder sb = new StringBuilder("{");
            int lim = Math.min(entries.size(), 8);
            for (int i = 0; i < lim; i++) {
              if (i > 0) sb.append(',');
              NamedValue nv = entries.get(i);
              sb.append(nv.key).append('=').append(nv.value.toSummaryString(depth + 1));
            }
            if (entries.size() > lim) sb.append(",...").append(entries.size());
            sb.append('}');
            return sb.toString();
          }
        case TAG_WORD_LIST:
          {
            int[] w = (int[]) ref;
            return "words[" + w.length + "]";
          }
        case TAG_WORD_LIST_DELTA:
          {
            WordListDelta d = (WordListDelta) ref;
            return "wordsDelta(base=" + d.baseIndex + ",patches=" + d.slotIndices.length + ")";
          }
        default:
          return "?tag=" + tag;
      }
    }
  }

  public static final class Op {
    final int opcode;
    final int viewId;
    final String viewType;
    final List<Prop> props;
    final int[] intData;
    final boolean hasFocusTarget;
    final Value scrollOptions;
    final List<ChildEdit> childEdits;

    private Op(
        int opcode,
        int viewId,
        String viewType,
        List<Prop> props,
        int[] intData,
        boolean hasFocusTarget,
        Value scrollOptions,
        List<ChildEdit> childEdits) {
      this.opcode = opcode;
      this.viewId = viewId;
      this.viewType = viewType;
      this.props = props;
      this.intData = intData;
      this.hasFocusTarget = hasFocusTarget;
      this.scrollOptions = scrollOptions;
      this.childEdits = childEdits;
    }

    public static Op create(int viewId, String viewType, List<Prop> props) {
      if (viewType == null) throw new IllegalArgumentException("viewType == null");
      List<Prop> copy = props != null ? new ArrayList<Prop>(props) : new ArrayList<Prop>();
      return new Op(OP_CREATE, viewId, viewType, copy, null, false, null, null);
    }

    public static Op update(int viewId, List<Prop> props) {
      List<Prop> copy = props != null ? new ArrayList<Prop>(props) : new ArrayList<Prop>();
      return new Op(OP_UPDATE, viewId, null, copy, null, false, null, null);
    }

    public static Op children(int parentId, int... childIds) {
      int[] copy = childIds != null ? Arrays.copyOf(childIds, childIds.length) : new int[0];
      return new Op(OP_CHILDREN, parentId, null, null, copy, false, null, null);
    }

    public static Op delete(int... viewIds) {
      int[] copy = viewIds != null ? Arrays.copyOf(viewIds, viewIds.length) : new int[0];
      return new Op(OP_DELETE, 0, null, null, copy, false, null, null);
    }

    public static Op root(int viewId) {
      return new Op(OP_ROOT, viewId, null, null, null, false, null, null);
    }

    public static Op focus(int viewId) {
      return new Op(OP_FOCUS, viewId, null, null, null, true, null, null);
    }

    public static Op clearFocus() {
      return new Op(OP_FOCUS, 0, null, null, null, false, null, null);
    }

    public static Op scrollTo(int viewId, Value options) {
      return new Op(
          OP_SCROLL_TO,
          viewId,
          null,
          null,
          null,
          false,
          options != null ? options : Value.ofPropMap(Collections.<Prop>emptyList()),
          null);
    }

    public static Op requestFrame() {
      return new Op(OP_REQUEST_FRAME, 0, null, null, null, false, null, null);
    }

    public static Op motion(int... words) {
      int[] copy = words != null ? Arrays.copyOf(words, words.length) : new int[0];
      return new Op(OP_MOTION, 0, null, null, copy, false, null, null);
    }

    public static Op childEdits(int parentId, List<ChildEdit> edits) {
      List<ChildEdit> copy =
          edits != null ? new ArrayList<ChildEdit>(edits) : new ArrayList<ChildEdit>();
      return new Op(OP_CHILD_EDITS, parentId, null, null, null, false, null, copy);
    }

    public int opcode() {
      return opcode;
    }

    public String opName() {
      return UiOps.opName(opcode);
    }

    public int viewId() {
      return viewId;
    }

    public String viewType() {
      return viewType;
    }

    public List<Prop> props() {
      return props;
    }

    public boolean hasFocusTarget() {
      return hasFocusTarget;
    }

    public Value scrollOptions() {
      return scrollOptions;
    }

    public List<ChildEdit> childEdits() {
      return childEdits;
    }

    public int[] childIds() {
      return opcode == OP_CHILDREN && intData != null
          ? Arrays.copyOf(intData, intData.length)
          : null;
    }

    public int[] deleteIds() {
      return opcode == OP_DELETE && intData != null ? Arrays.copyOf(intData, intData.length) : null;
    }

    public int[] motionWords() {
      return opcode == OP_MOTION && intData != null ? Arrays.copyOf(intData, intData.length) : null;
    }

    public Prop findProp(int propId) {
      if (props == null) return null;
      for (int i = 0; i < props.size(); i++) {
        Prop p = props.get(i);
        if (p.propId == propId) return p;
      }
      return null;
    }

    public Prop findProp(String propName) {
      int id = UiOps.propId(propName);
      return id >= 0 ? findProp(id) : null;
    }

    public String propString(String propName) {
      Prop p = findProp(propName);
      return p != null ? p.value.asString() : null;
    }

    public boolean containsString(String needle) {
      if (props == null) return false;
      for (int i = 0; i < props.size(); i++) {
        if (props.get(i).value.containsString(needle)) return true;
      }
      return false;
    }
  }

  public static final class ViewEvent {
    public static final int EVENT_VIEW = 1;
    public static final int EVENT_BACK = 2;
    public static final int EVENT_KEY_COMMAND = 3;
    public static final int EVENT_WINDOW_INSETS = 4;
    public static final int EVENT_METRIC = 6;
    public static final int EVENT_FRAME = 7;

    private final byte[] raw;
    private final int eventKind;
    private final int viewId;
    private final int propId;
    private final Value payload;
    private final String keyCommand;
    private final float[] insets;
    private final double metric;

    private ViewEvent(
        byte[] raw,
        int eventKind,
        int viewId,
        int propId,
        Value payload,
        String keyCommand,
        float[] insets,
        double metric) {
      this.raw = raw;
      this.eventKind = eventKind;
      this.viewId = viewId;
      this.propId = propId;
      this.payload = payload;
      this.keyCommand = keyCommand;
      this.insets = insets;
      this.metric = metric;
    }

    public static boolean isViewEventFrame(byte[] b) {
      return b != null
          && b.length >= 6
          && b[0] == 0
          && b[1] == 0
          && b[2] == 0
          && b[3] == 0
          && (b[4] & 0xff) == WIRE_VERSION;
    }

    public static ViewEvent parse(byte[] b) {
      if (!isViewEventFrame(b)) return null;
      ByteBuffer buf = ByteBuffer.wrap(b, 5, b.length - 5).order(ByteOrder.LITTLE_ENDIAN);
      int kind = buf.get() & 0xff;
      switch (kind) {
        case EVENT_VIEW:
          {
            if (buf.remaining() < 7) return null;
            int viewId = buf.getInt();
            int propId = buf.getShort() & 0xffff;
            Value payload = readValue(buf, 0);
            if (payload == null) return null;
            return new ViewEvent(b, kind, viewId, propId, payload, null, null, 0.0);
          }
        case EVENT_BACK:
        case EVENT_FRAME:
          return new ViewEvent(b, kind, 0, -1, null, null, null, 0.0);
        case EVENT_KEY_COMMAND:
          {
            if (buf.remaining() < 2) return null;
            int len = buf.getShort() & 0xffff;
            String cmd = readUtf8(buf, len);
            if (cmd == null) return null;
            return new ViewEvent(b, kind, 0, -1, null, cmd, null, 0.0);
          }
        case EVENT_WINDOW_INSETS:
          {
            if (buf.remaining() < 20) return null;
            float[] ins = new float[5];
            for (int i = 0; i < 5; i++) {
              ins[i] = buf.getFloat();
            }
            return new ViewEvent(b, kind, 0, -1, null, null, ins, 0.0);
          }
        case EVENT_METRIC:
          {
            if (buf.remaining() < 8) return null;
            return new ViewEvent(b, kind, 0, -1, null, null, null, buf.getDouble());
          }
        default:
          return new ViewEvent(b, kind, 0, -1, null, null, null, 0.0);
      }
    }

    public static byte[] buildViewEvent(int viewId, int propId, Value payload) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(32);
      byte[] scratch = new byte[8];
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(WIRE_VERSION);
      out.write(EVENT_VIEW);
      writeIntLE(out, scratch, viewId);
      writeShortLE(out, scratch, propId);
      writeValue(out, scratch, payload != null ? payload : Value.ofNull());
      return out.toByteArray();
    }

    public static byte[] buildBackEvent() {
      return new byte[] {0, 0, 0, 0, WIRE_VERSION, EVENT_BACK};
    }

    public static byte[] buildFrameEvent() {
      return new byte[] {0, 0, 0, 0, WIRE_VERSION, EVENT_FRAME};
    }

    public static byte[] buildKeyCommandEvent(String command) {
      byte[] utf8 = (command != null ? command : "").getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream out = new ByteArrayOutputStream(8 + utf8.length);
      byte[] scratch = new byte[8];
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(0);
      out.write(WIRE_VERSION);
      out.write(EVENT_KEY_COMMAND);
      writeShortLE(out, scratch, utf8.length);
      out.write(utf8, 0, utf8.length);
      return out.toByteArray();
    }

    public byte[] raw() {
      return raw;
    }

    public int eventKind() {
      return eventKind;
    }

    public boolean isViewEvent() {
      return eventKind == EVENT_VIEW;
    }

    public boolean isBackEvent() {
      return eventKind == EVENT_BACK;
    }

    public int viewId() {
      return viewId;
    }

    public boolean isSyntheticView() {
      return eventKind == EVENT_VIEW && UiOps.isSyntheticId(viewId);
    }

    public int propId() {
      return propId;
    }

    public String eventName() {
      return propId >= 0 ? UiOps.propName(propId) : null;
    }

    public Value payload() {
      return payload;
    }

    public String keyCommand() {
      return keyCommand;
    }

    public float[] insets() {
      return insets != null ? Arrays.copyOf(insets, insets.length) : null;
    }

    public double metric() {
      return metric;
    }
  }
}
