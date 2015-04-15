// Copyright (c) 2013, Cloudera, inc.
// Confidential Cloudera Information: Covered by NDA.
package org.kududb.client;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.google.protobuf.ZeroCopyLiteralByteString;
import org.kududb.ColumnSchema;
import org.kududb.Schema;
import org.kududb.Type;
import kudu.WireProtocol.RowOperationsPB;
import kudu.tserver.Tserver;
import org.kududb.util.Pair;
import org.jboss.netty.buffer.ChannelBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Base class for the RPCs that related to WriteRequestPB. It contains almost all the logic
 * and knows how to serialize its child classes.
 * Each Operation is backed by an Arena where all the cells (except strings) are written. The
 * strings are kept in a List.
 */
public abstract class Operation extends KuduRpc<OperationResponse> implements KuduRpc.HasKey {

  enum ChangeType {
    INSERT((byte)RowOperationsPB.Type.INSERT.getNumber()),
    UPDATE((byte)RowOperationsPB.Type.UPDATE.getNumber()),
    DELETE((byte)RowOperationsPB.Type.DELETE.getNumber());

    ChangeType(byte encodedByte) {
      this.encodedByte = encodedByte;
    }

    byte toEncodedByte() {
      return encodedByte;
    }

    /** The byte used to encode this in a RowOperationsPB */
    private byte encodedByte;
  }

  static final String METHOD = "Write";
  final Schema schema;
  final List<String> strings;
  final int rowSize;
  final byte[] rowAlloc;
  final BitSet columnsBitSet;
  final BitSet nullsBitSet;

  // The following attributes are used to speed up serialization
  int stringsSize;
  // This is used for update/delete that have strings in their key
  int keyOnlyStringSize;
  // Size of the RowChangeList. Starts at one for the ChangeType's ordinal, only used for update/delete.
  int encodedSize = 1;
  int partialRowSize = 0;

  /**
   * Package-private constructor. Subclasses need to be instantiated via AsyncKuduSession
   * @param table table with the schema to use for this operation
   */
  Operation(KuduTable table) {
    super(table);
    this.schema = table.getSchema();
    this.columnsBitSet = new BitSet(this.schema.getColumnCount());
    this.nullsBitSet = schema.hasNullableColumns() ?
        new BitSet(this.schema.getColumnCount()) : null;
    this.rowSize = schema.getRowSize();
    this.rowAlloc = new byte[this.rowSize];
    strings = new ArrayList<String>(schema.getStringCount());
  }

  /**
   * Classes extending Operation need to have a specific ChangeType
   * @return Operation's ChangeType
   */
  abstract ChangeType getChangeType();

  /**
   * Add a boolean for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addBoolean(String columnName, boolean val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.BOOL);
    rowAlloc[getPositionInRowAllocAndSetBitSet(col)] = (byte) (val ? 1 : 0);
  }

  /**
   * Add a byte for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addByte(String columnName, byte val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.INT8);
    rowAlloc[getPositionInRowAllocAndSetBitSet(col)] = val;
  }

  /**
   * Add a short for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addShort(String columnName, short val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.INT16);
    Bytes.setShort(rowAlloc, val, getPositionInRowAllocAndSetBitSet(col));
  }

  /**
   * Add an int for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addInt(String columnName, int val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.INT32);
    Bytes.setInt(rowAlloc, val, getPositionInRowAllocAndSetBitSet(col));
  }

  /**
   * Add an long for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addLong(String columnName, long val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.INT64);
    Bytes.setLong(rowAlloc, val, getPositionInRowAllocAndSetBitSet(col));
  }

  /**
   * Add an float for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addFloat(String columnName, float val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.FLOAT);
    Bytes.setFloat(rowAlloc, val, getPositionInRowAllocAndSetBitSet(col));
  }

  /**
   * Add an double for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addDouble(String columnName, double val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.DOUBLE);
    Bytes.setDouble(rowAlloc, val, getPositionInRowAllocAndSetBitSet(col));
  }

  /**
   * Add a String for the specified column.
   * @param columnName Name of the column
   * @param val value to add
   * @throws IllegalArgumentException if the column doesn't exist or the value doesn't match
   * the column's type
   */
  public void addString(String columnName, String val) {
    ColumnSchema col = this.schema.getColumn(columnName);
    checkColumn(col, Type.STRING);
    int size = val.length();
    this.strings.add(val);
    this.stringsSize += size;
    // special accounting for string row keys in updates or deletes
    if (col.isKey()) {
      keyOnlyStringSize += size;
    } else {
      encodedSize += CodedOutputStream.computeRawVarint32Size(size);
      encodedSize += size;
    }
    // Set the bit and set the usage bit
    getPositionInRowAllocAndSetBitSet(col);
  }

  /**
   * Set the specified column to null
   * @param columnName Name of the column
   * @throws IllegalArgumentException if the column doesn't exist or cannot be set to null
   */
  public void setNull(String columnName) {
    assert nullsBitSet != null;
    ColumnSchema col = schema.getColumn(columnName);
    checkColumnExists(col);
    if (!col.isNullable()) {
      throw new IllegalArgumentException(col.getName() + " cannot be set to null");
    }
    int idx = schema.getColumns().indexOf(col);
    columnsBitSet.set(idx);
    nullsBitSet.set(idx);
  }

  /**
   * Verifies if the column exists and belongs to the specified type
   * It also does some internal accounting
   * @param column column the user wants to set
   * @param type type we expect
   * @throws IllegalArgumentException if the column or type was invalid
   */
  void checkColumn(ColumnSchema column, Type type) {
    checkColumnExists(column);
    if (!column.getType().equals(type))
      throw new IllegalArgumentException(column.getName() +
          " isn't " + type.getName() + ", it's " + column.getType().getName());
    if (!column.isKey()) {
      encodedSize++; // col id
      if (!column.getType().equals(Type.STRING)) {
        encodedSize += column.getType().getSize(); // strings have their own processing in addString
      }
    }
  }

  /**
   * @param column column the user wants to set
   * @throws IllegalArgumentException if the column doesn't exist
   */
  void checkColumnExists(ColumnSchema column) {
    if (column == null)
      throw new IllegalArgumentException("Column name isn't present in the table's schema");
  }

  /**
   * Gives the column's location in the byte array and marks it as set
   * @param column column to get the position for and mark as set
   * @return the offset in rowAlloc for the column
   */
  int getPositionInRowAllocAndSetBitSet(ColumnSchema column) {
    int idx = schema.getColumns().indexOf(column);
    columnsBitSet.set(idx);
    partialRowSize += column.getType().getSize();
    return schema.getColumnOffset(idx);
  }

  /**
   * Tells if the specified column was set by the user
   * @param column column's index in the schema
   * @return true if it was set, else false
   */
  boolean isSet(int column) {
    return this.columnsBitSet.get(column);
  }

  /**
   * Tells if the specified column was set to null by the user
   * @param column column's index in the schema
   * @return true if it was set, else false
   */
  boolean isSetToNull(int column) {
    if (this.nullsBitSet == null) {
      return false;
    }
    return this.nullsBitSet.get(column);
  }

  @Override
  String serviceName() { return TABLET_SERVER_SERVICE_NAME; }

  @Override
  String method() {
    return METHOD;
  }

  @Override
  ChannelBuffer serialize(Message header) {
    final Tserver.WriteRequestPB.Builder builder = createAndFillWriteRequestPB(this);
    builder.setTabletId(ZeroCopyLiteralByteString.wrap(getTablet().getTabletIdAsBytes()));
    builder.setExternalConsistencyMode(this.externalConsistencyMode.pbVersion());
    if (this.propagatedTimestamp != AsyncKuduClient.NO_TIMESTAMP) {
      builder.setPropagatedTimestamp(this.propagatedTimestamp);
    }
    return toChannelBuffer(header, builder.build());
  }

  @Override
  Pair<OperationResponse, Object> deserialize(CallResponse callResponse,
                                              String tsUUID) throws Exception {
    Tserver.WriteResponsePB.Builder builder = Tserver.WriteResponsePB.newBuilder();
    readProtobuf(callResponse.getPBMessage(), builder);
    if (builder.getPerRowErrorsCount() != 0) {
      List<Operation> ops = new ArrayList<Operation>(1);
      ops.add(this);
      throw RowsWithErrorException.fromPerRowErrorPB(builder.getPerRowErrorsList(), ops, tsUUID);
    }
    OperationResponse response = new OperationResponse(deadlineTracker.getElapsedMillis(), tsUUID,
        builder.getTimestamp());
    return new Pair<OperationResponse, Object>(response, builder.getError());
  }

  @Override
  public byte[] key() {
    int seenStrings = 0;
    KeyEncoder keyEncoder = new KeyEncoder(this.schema);
    for (int i = 0; i < this.schema.getKeysCount(); i++) {
      ColumnSchema column = this.schema.getColumn(i);
      if (column.getType() == Type.STRING) {
        String string = this.strings.get(seenStrings);
        seenStrings++;
        keyEncoder.addKey(Bytes.fromString(string), 0, string.length(), column, i);
      } else {
        keyEncoder.addKey(this.rowAlloc, this.schema.getColumnOffset(i), column.getType().getSize(),
            column, i);
      }
    }
    // TODO we might want to cache the key
    return keyEncoder.extractByteArray();
  }

  /**
   * Helper method that puts a list of Operations together into a WriteRequestPB.
   * @param operations The list of ops to put together in a WriteRequestPB
   * @return A fully constructed WriteRequestPB containing the passed rows, or
   *         null if no rows were passed.
   */
  static Tserver.WriteRequestPB.Builder createAndFillWriteRequestPB(Operation... operations) {
    if (operations == null || operations.length == 0) return null;

    Schema schema = operations[0].schema;

    // Pre-calculate the sizes for the buffers.
    int rowSize = 0;
    int indirectSize = 0;
    final int columnBitSetSize = Bytes.getBitSetSize(schema.getColumnCount());
    for (Operation operation : operations) {
      rowSize += 1 /* for the op type */ + operation.partialRowSize + columnBitSetSize;
      if (schema.hasNullableColumns()) {
        // nullsBitSet is the same size as the columnBitSet
        rowSize += columnBitSetSize;
      }
      indirectSize += operation.stringsSize;
    }

    // Set up the encoded data.
    ByteBuffer rows = ByteBuffer.allocate(rowSize).order(ByteOrder.LITTLE_ENDIAN);
    ByteBuffer indirectData = indirectSize == 0 ?
        null : ByteBuffer.allocate(indirectSize).order(ByteOrder.LITTLE_ENDIAN);

    for (Operation operation : operations) {
      assert operation.schema == schema;

      rows.put(operation.getChangeType().toEncodedByte());
      rows.put(Bytes.fromBitSet(operation.columnsBitSet, schema.getColumnCount()));
      if (schema.hasNullableColumns()) {
        rows.put(Bytes.fromBitSet(operation.nullsBitSet, schema.getColumnCount()));
      }
      int colIdx = 0;
      byte[] rowData = operation.rowAlloc;
      int stringsIndex = 0;
      int currentRowOffset = 0;
      for (ColumnSchema col : operation.schema.getColumns()) {
        // Keys should always be specified, maybe check?
        if (operation.isSet(colIdx) && !operation.isSetToNull(colIdx)) {
          if (col.getType() == Type.STRING) {
            String string = operation.strings.get(stringsIndex);
            rows.putLong(indirectData.position());
            rows.putLong(string.length());
            indirectData.put(Bytes.fromString(string), 0, string.length());
            stringsIndex++;
          } else {
            // This is for cols other than strings
            rows.put(rowData, currentRowOffset, col.getType().getSize());
          }
        }
        currentRowOffset += col.getType().getSize();
        colIdx++;
      }
    }
    assert !rows.hasRemaining();

    // Actually build the protobuf.
    RowOperationsPB.Builder rowOpsBuilder = RowOperationsPB.newBuilder();
    rowOpsBuilder.setRows(ZeroCopyLiteralByteString.wrap(rows.array()));
    if (indirectData != null) {
      assert !indirectData.hasRemaining();
      rowOpsBuilder.setIndirectData(ZeroCopyLiteralByteString.wrap(indirectData.array()));
    }

    Tserver.WriteRequestPB.Builder requestBuilder = Tserver.WriteRequestPB.newBuilder();
    requestBuilder.setSchema(ProtobufHelper.schemaToPb(schema));
    requestBuilder.setRowOperations(rowOpsBuilder);
    return requestBuilder;
  }
}