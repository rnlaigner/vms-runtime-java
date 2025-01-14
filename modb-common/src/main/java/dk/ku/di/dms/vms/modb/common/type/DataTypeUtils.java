package dk.ku.di.dms.vms.modb.common.type;

import dk.ku.di.dms.vms.modb.common.memory.MemoryUtils;

import java.util.Date;

import static dk.ku.di.dms.vms.modb.common.type.Constants.DEFAULT_MAX_SIZE_STRING;

public final class DataTypeUtils {

    private DataTypeUtils(){}

    private static final jdk.internal.misc.Unsafe UNSAFE = MemoryUtils.UNSAFE;

    public static Object getValue(DataType dt, long address){
        switch (dt) {
            case BOOL -> {
                return UNSAFE.getBoolean(null, address);
            }
            case INT -> {
                return UNSAFE.getInt(null, address);
            }
            case CHAR -> {
                return UNSAFE.getChar(null, address);
            }
            case STRING, ENUM -> {
                StringBuilder sb = new StringBuilder();
                long currAddress = address;
                for(int i = 0; i < DEFAULT_MAX_SIZE_STRING; i++) {
                    sb.append( UNSAFE.getChar(null, currAddress) );
                    currAddress += Character.BYTES;
                }
                // temporary solution due to the lack of string size metadata
                for(int i = DEFAULT_MAX_SIZE_STRING-1; i >= 0; i--) {
                    if(sb.charAt(sb.length()-1) == '\0'){
                        sb.delete(sb.length()-1, sb.length());
                    }
                }
                return sb.toString();
            }
            case LONG -> {
                return UNSAFE.getLong(null, address);
            }
            case DATE -> {
                long dateLong = UNSAFE.getLong(null, address);
                return new Date(dateLong);
            }
            case FLOAT -> {
                return UNSAFE.getFloat(null, address);
            }
            case DOUBLE -> {
                return UNSAFE.getDouble(null, address);
            }
            default -> throw new IllegalStateException("Unknown data type");
        }
    }

    // just a wrapper
    public static void callWriteFunction(long address, DataType dt, Object value){
        switch (dt){
            case BOOL -> // byte is used. on unsafe, the boolean is used
                    UNSAFE.putBoolean(null, address, (boolean)value);
            case INT -> UNSAFE.putInt(null, address, (int)value);
            case CHAR -> UNSAFE.putChar(null, address, (char)value);
            case STRING, ENUM -> {
                long currPos = address;
                int i = 0;
                switch (value) {
                    case String strValue -> {
                        int length = Math.min(strValue.length(), DEFAULT_MAX_SIZE_STRING);
                        while (i < length) {
                            UNSAFE.putChar(null, currPos, strValue.charAt(i));
                            currPos += Character.BYTES;
                            i++;
                        }
                    }
                    case Enum<?> anEnum -> {
                        String strValue = value.toString();
                        int length = Math.min(strValue.length(), DEFAULT_MAX_SIZE_STRING);
                        while (i < length) {
                            UNSAFE.putChar(null, currPos, strValue.charAt(i));
                            currPos += Character.BYTES;
                            i++;
                        }
                    }
                    case Character[] charArray -> {
                        int length = Math.min(charArray.length, DEFAULT_MAX_SIZE_STRING);
                        while (i < length) {
                            UNSAFE.putChar(null, currPos, charArray[i]);
                            currPos += Character.BYTES;
                            i++;
                        }
                    }
                    case null, default -> {
                        assert value != null;
                        throw new IllegalStateException("Cannot write type " + value.getClass());
                    }
                }
            }
            case LONG -> UNSAFE.putLong(null, address, (long)value);
            case DATE -> {
                if(value instanceof Date date){
                    UNSAFE.putLong(null, address, date.getTime());
                } else {
                    throw new IllegalStateException("Date can only be of type Date");
                }
            }
            case FLOAT -> UNSAFE.putFloat(null, address, (float)value);
            case DOUBLE -> UNSAFE.putDouble(null, address, (double)value);
            default -> throw new IllegalStateException("Unknown data type");
        }
    }

    public static DataType getColumnDataTypeFromAttributeType(Class<?> attributeType) {
        String attributeCanonicalName = attributeType.getCanonicalName();
        if (attributeCanonicalName.equalsIgnoreCase("int") || attributeType == Integer.class){
            return DataType.INT;
        }
        else if (attributeCanonicalName.equalsIgnoreCase("float") || attributeType == Float.class){
            return DataType.FLOAT;
        }
        else if (attributeCanonicalName.equalsIgnoreCase("double") || attributeType == Double.class){
            return DataType.DOUBLE;
        }
        else if (attributeCanonicalName.equalsIgnoreCase("char") || attributeType == Character.class){
            return DataType.CHAR;
        }
        else if (attributeCanonicalName.equalsIgnoreCase("long") || attributeType == Long.class){
            return DataType.LONG;
        }
        else if (attributeType == Date.class){
            return DataType.DATE;
        }
        else if(attributeType == String.class){
            return DataType.STRING;
        }
        else if(attributeType.isEnum()){
            return DataType.ENUM;
        }
        else {
            throw new IllegalStateException(attributeType.getCanonicalName() + " is not accepted as a column data type.");
        }
    }

    public static Class<?> getJavaTypeFromDataType(DataType dataType) {
        switch (dataType) {
            case BOOL -> {
                return boolean.class;
            }
            case INT -> {
                return int.class;
            }
            case CHAR -> {
                return char.class;
            }
            case STRING -> {
                return String.class;
            }
            case LONG -> {
                return long.class;
            }
            case FLOAT -> {
                return float.class;
            }
            case DOUBLE -> {
                return double.class;
            }
            case DATE -> {
                return Date.class;
            }
            case ENUM -> {
                return Enum.class;
            }
            default -> throw new IllegalStateException(dataType + " is not supported.");
        }
    }
}
