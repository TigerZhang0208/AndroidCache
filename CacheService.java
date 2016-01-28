package tech.zhiqu.cache;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by tiger007 on 1/26/16.
 */
public class CacheService {
    private final AtomicLong cacheSize;
    private final AtomicInteger cacheCount;
    private final long sizeLimit;
    private final int countLimit;
    private final Map<File, Long> lastUsageDates = Collections
            .synchronizedMap(new HashMap<File, Long>());
    protected File cacheDir;

    public CacheService(File cacheDir, long sizeLimit, int countLimit) {
        this.cacheDir = cacheDir;
        this.sizeLimit = sizeLimit;
        this.countLimit = countLimit;
        cacheSize = new AtomicLong();
        cacheCount = new AtomicInteger();
        calculateCacheSizeAndCacheCount();
    }


    public boolean existsKey(String key) {
        File[] cachedFiles = cacheDir.listFiles();
        if (cachedFiles != null) {
            for (File cachedFile : cachedFiles) {
                String name = cachedFile.getName();
                if (name != null && name.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }


    public ArrayList<String> findKeys(String key) {
        ArrayList<String> keyList = new ArrayList<>();
        File[] cachedFiles = cacheDir.listFiles();
        if (cachedFiles != null) {
            for (File cachedFile : cachedFiles) {
                String name = cachedFile.getName();
                if (name != null && name.startsWith(key)) {
                    keyList.add(name);
                }
            }
        }
        return keyList;
    }

    public ArrayList<String> getValueList(String key) {
        ArrayList<String> valueList = new ArrayList<>();
        File[] cachedFiles = cacheDir.listFiles();
        if (cachedFiles != null) {
            for (File cachedFile : cachedFiles) {
                String name = cachedFile.getName();
                if (name != null && name.startsWith(key)) {
                    valueList.add(getValue(key));
                }
            }
        }
        return valueList;
    }

    /**
     * 计算 cacheSize和cacheCount
     */
    public void calculateCacheSizeAndCacheCount() {
        int size = 0;
        int count = 0;
        File[] cachedFiles = cacheDir.listFiles();
        if (cachedFiles != null) {
            for (File cachedFile : cachedFiles) {
                size += calculateSize(cachedFile);
                count += 1;
                lastUsageDates.put(cachedFile,
                        cachedFile.lastModified());
            }
            cacheSize.set(size);
            cacheCount.set(count);
        }
    }

    public void put(File file) {
        int curCacheCount = cacheCount.get();
        while (curCacheCount + 1 > countLimit) {
            long freedSize = removeNext();
            cacheSize.addAndGet(-freedSize);

            curCacheCount = cacheCount.addAndGet(-1);
        }
        cacheCount.addAndGet(1);

        long valueSize = calculateSize(file);
        long curCacheSize = cacheSize.get();
        while (curCacheSize + valueSize > sizeLimit) {
            long freedSize = removeNext();
            curCacheSize = cacheSize.addAndGet(-freedSize);
        }
        cacheSize.addAndGet(valueSize);

        Long currentTime = System.currentTimeMillis();
        file.setLastModified(currentTime);
        lastUsageDates.put(file, currentTime);
    }

    public File get(String key) {
        File file = newFile(key);
        Long currentTime = System.currentTimeMillis();
        file.setLastModified(currentTime);
        lastUsageDates.put(file, currentTime);

        return file;
    }

    /*
    public File newFile(String key) {
        return new File(cacheDir, key.hashCode() + "");
    }
    */


    public File newFile(String key) {
        return new File(cacheDir, key);
    }

    public boolean remove(String key) {
        File image = get(key);
        return image.delete();
    }

    public void clear() {
        lastUsageDates.clear();
        cacheSize.set(0);
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    /**
     * 移除旧的文件
     *
     * @return
     */
    public long removeNext() {
        if (lastUsageDates.isEmpty()) {
            return 0;
        }

        Long oldestUsage = null;
        File mostLongUsedFile = null;
        Set<Map.Entry<File, Long>> entries = lastUsageDates.entrySet();
        synchronized (lastUsageDates) {
            for (Map.Entry<File, Long> entry : entries) {
                if (mostLongUsedFile == null) {
                    mostLongUsedFile = entry.getKey();
                    oldestUsage = entry.getValue();
                } else {
                    Long lastValueUsage = entry.getValue();
                    if (lastValueUsage < oldestUsage) {
                        oldestUsage = lastValueUsage;
                        mostLongUsedFile = entry.getKey();
                    }
                }
            }
        }

        long fileSize = calculateSize(mostLongUsedFile);
        if (mostLongUsedFile.delete()) {
            lastUsageDates.remove(mostLongUsedFile);
        }
        return fileSize;
    }

    public long calculateSize(File file) {
        return file.length();
    }

    public void setKeyValueByte(String key, byte[] value) {
        File file = newFile(key);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(value);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            put(file);
        }
    }


    public synchronized void setKeyValueObject(String key, Serializable value, int saveTime) {
        ByteArrayOutputStream baos = null;
        ObjectOutputStream oos = null;
        try {
            baos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(baos);
            oos.writeObject(value);
            byte[] data = baos.toByteArray();
            if (saveTime != -1) {
                setKeyValueByte(key, newByteArrayWithDateInfo(saveTime, data));
            } else {
                setKeyValueByte(key, data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                oos.close();
            } catch (IOException e) {
            }
        }
    }

    public synchronized void setKeyValue(String key, String value) {
        File file = newFile(key);
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(file), value.length());
            out.write(value);
        } catch (Exception e) {
            e.getStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException e) {

                }
            }
            put(file);
        }


    }

    public String getValue(String key) {
        File file = get(key);
        if (file.exists()) {
            boolean removeFile = false;
            BufferedReader in = null;
            try {
                in = new BufferedReader(new FileReader(file));
                String readString = "";
                String currentLine;
                while ((currentLine = in.readLine()) != null) {
                    readString += currentLine;
                }
                if (!isDue(readString)) {
                    return clearDateInfo(readString);
                } else {
                    removeFile = true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (removeFile)
                    remove(key);
            }
        }

        return null;
    }

    public Object getObject(String key) {
        byte[] data = getBinary(key);
        if (data != null) {
            ByteArrayInputStream bais = null;
            ObjectInputStream ois = null;
            try {
                bais = new ByteArrayInputStream(data);
                ois = new ObjectInputStream(bais);
                Object reObject = ois.readObject();
                return reObject;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            } finally {
                try {
                    if (bais != null)
                        bais.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    if (ois != null)
                        ois.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;

    }

    public byte[] getBinary(String key) {
        RandomAccessFile RAFile = null;
        boolean removeFile = false;
        try {
            File file = get(key);
            if (!file.exists())
                return null;
            RAFile = new RandomAccessFile(file, "r");
            byte[] byteArray = new byte[(int) RAFile.length()];
            RAFile.read(byteArray);
            if (!isDue(byteArray)) {
                return clearDateInfo(byteArray);
            } else {
                removeFile = true;
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (RAFile != null) {
                try {
                    RAFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (removeFile)
                remove(key);
        }
    }

    public synchronized void appendContents(String sFileName, String sContent) {
        try {

            File oFile = new File(sFileName);
            if (!oFile.exists()) {
                oFile.createNewFile();
            }
            if (oFile.canWrite()) {
                BufferedWriter oWriter = new BufferedWriter(new FileWriter(sFileName, true));
                oWriter.write(sContent);
                oWriter.close();
            }

        } catch (IOException oException) {
            throw new IllegalArgumentException("Error appending/File cannot be written: \n" + sFileName);
        }
    }


    /**
     * 判断缓存的String数据是否到期
     *
     * @param str
     * @return true：到期了 false：还没有到期
     */
    public  boolean isDue(String str) {
        return isDue(str.getBytes());
    }

    /**
     * 判断缓存的byte数据是否到期
     *
     * @param data
     * @return true：到期了 false：还没有到期
     */
    public boolean isDue(byte[] data) {
        String[] strs = getDateInfoFromDate(data);
        if (strs != null && strs.length == 2) {
            String saveTimeStr = strs[0];
            while (saveTimeStr.startsWith("0")) {
                saveTimeStr = saveTimeStr
                        .substring(1, saveTimeStr.length());
            }
            long saveTime = Long.valueOf(saveTimeStr);
            long deleteAfter = Long.valueOf(strs[1]);
            if (System.currentTimeMillis() > saveTime + deleteAfter * 1000) {
                return true;
            }
        }
        return false;
    }

    public String newStringWithDateInfo(int second, String strInfo) {
        return createDateInfo(second) + strInfo;
    }

    public byte[] newByteArrayWithDateInfo(int second, byte[] data2) {
        byte[] data1 = createDateInfo(second).getBytes();
        byte[] retdata = new byte[data1.length + data2.length];
        System.arraycopy(data1, 0, retdata, 0, data1.length);
        System.arraycopy(data2, 0, retdata, data1.length, data2.length);
        return retdata;
    }

    public String clearDateInfo(String strInfo) {
        if (strInfo != null && hasDateInfo(strInfo.getBytes())) {
            strInfo = strInfo.substring(strInfo.indexOf(mSeparator) + 1,
                    strInfo.length());
        }
        return strInfo;
    }

    public byte[] clearDateInfo(byte[] data) {
        if (hasDateInfo(data)) {
            return copyOfRange(data, indexOf(data, mSeparator) + 1,
                    data.length);
        }
        return data;
    }

    public boolean hasDateInfo(byte[] data) {
        return data != null && data.length > 15 && data[13] == '-'
                && indexOf(data, mSeparator) > 14;
    }

    public String[] getDateInfoFromDate(byte[] data) {
        if (hasDateInfo(data)) {
            String saveDate = new String(copyOfRange(data, 0, 13));
            String deleteAfter = new String(copyOfRange(data, 14,
                    indexOf(data, mSeparator)));
            return new String[]{saveDate, deleteAfter};
        }
        return null;
    }

    public int indexOf(byte[] data, char c) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == c) {
                return i;
            }
        }
        return -1;
    }

    public byte[] copyOfRange(byte[] original, int from, int to) {
        int newLength = to - from;
        if (newLength < 0)
            throw new IllegalArgumentException(from + " > " + to);
        byte[] copy = new byte[newLength];
        System.arraycopy(original, from, copy, 0,
                Math.min(original.length - from, newLength));
        return copy;
    }

    public final char mSeparator = ' ';

    public String createDateInfo(int second) {
        String currentTime = System.currentTimeMillis() + "";
        while (currentTime.length() < 13) {
            currentTime = "0" + currentTime;
        }
        return currentTime + "-" + second + mSeparator;
    }

    /*
     * Bitmap → byte[]
     */
    public byte[] Bitmap2Bytes(Bitmap bm) {
        if (bm == null) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return baos.toByteArray();
    }

    /*
     * byte[] → Bitmap
     */
    public Bitmap Bytes2Bimap(byte[] b) {
        if (b.length == 0) {
            return null;
        }
        return BitmapFactory.decodeByteArray(b, 0, b.length);
    }

    /*
     * Drawable → Bitmap
     */
    public Bitmap drawable2Bitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        // 取 drawable 的长宽
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        // 取 drawable 的颜色格式
        Bitmap.Config config = drawable.getOpacity() != PixelFormat.OPAQUE ? Bitmap.Config.ARGB_8888
                : Bitmap.Config.RGB_565;
        // 建立对应 bitmap
        Bitmap bitmap = Bitmap.createBitmap(w, h, config);
        // 建立对应 bitmap 的画布
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        // 把 drawable 内容画到画布中
        drawable.draw(canvas);
        return bitmap;
    }

    /*
     * Bitmap → Drawable
     */
    @SuppressWarnings("deprecation")
    public Drawable bitmap2Drawable(Bitmap bm) {
        if (bm == null) {
            return null;
        }
        return new BitmapDrawable(bm);
    }
}


