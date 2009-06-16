package com.custardsource.parfait.pcp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.custardsource.parfait.pcp.types.DefaultTypeHandlers;
import com.custardsource.parfait.pcp.types.TypeHandler;

public abstract class BasePcpWriter implements PcpWriter {
    private final File dataFile;
    private final Map<MetricName, PcpValueInfo> metricData = new LinkedHashMap<MetricName, PcpValueInfo>();
    private final Map<String, PcpMetricInfo> metricNames = new LinkedHashMap<String, PcpMetricInfo>();
    private final Map<Class<?>, TypeHandler<?>> typeHandlers = new HashMap<Class<?>, TypeHandler<?>>(
            DefaultTypeHandlers.getDefaultMappings());
    protected volatile boolean started = false;
    private ByteBuffer dataFileBuffer = null;

    protected BasePcpWriter(File dataFile) {
        this.dataFile = dataFile;
    }

    /*
     * (non-Javadoc)
     * @see com.custardsource.parfait.pcp.PcpWriter#addMetric(java.lang.String, java.lang.Object)
     */
    public void addMetric(MetricName name, Object initialValue) {
        TypeHandler<?> handler = typeHandlers.get(initialValue.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("No default handler registered for type "
                    + initialValue.getClass());
        }
        addMetricInfo(name, initialValue, handler);

    }

    /*
     * (non-Javadoc)
     * @see com.custardsource.parfait.pcp.PcpWriter#addMetric(java.lang.String, T,
     * com.custardsource.parfait.pcp.types.TypeHandler)
     */
    public <T> void addMetric(MetricName name, T initialValue, TypeHandler<T> pcpType) {
        if (pcpType == null) {
            throw new IllegalArgumentException("PCP Type handler must not be null");
        }
        addMetricInfo(name, initialValue, pcpType);
    }

    /*
     * (non-Javadoc)
     * @see com.custardsource.parfait.pcp.PcpWriter#registerType(java.lang.Class,
     * com.custardsource.parfait.pcp.types.TypeHandler)
     */
    public <T> void registerType(Class<T> runtimeClass, TypeHandler<T> handler) {
        if (started) {
            // Can't add any more metrics anyway; harmless
            return;
        }
        typeHandlers.put(runtimeClass, handler);
    }

    /*
     * (non-Javadoc)
     * @see com.custardsource.parfait.pcp.PcpWriter#updateMetric(java.lang.String, java.lang.Object)
     */
    public void updateMetric(MetricName name, Object value) {
        if (!started) {
            throw new IllegalStateException("Cannot update metric unless writer is running");
        }
        PcpValueInfo info = metricData.get(name);
        if (info == null) {
            throw new IllegalArgumentException("Metric " + name
                    + " was not added before initialising the writer");
        }
        updateValue(info, value);
    }

    @SuppressWarnings("unchecked")
    protected void updateValue(PcpValueInfo info, Object value) {
        dataFileBuffer.position(info.getOffsets().dataValueOffset());
        TypeHandler rawHandler = info.getTypeHandler();
        rawHandler.putBytes(dataFileBuffer, value);
    }

    private synchronized void addMetricInfo(MetricName name, Object initialValue,
            TypeHandler<?> pcpType) {
        if (started) {
            throw new IllegalStateException("Cannot add metric " + name + " after starting");
        }
        if (metricData.containsKey(name)) {
            throw new IllegalArgumentException("Metric " + name
                    + " has already been added to writer");
        }
        if (name.getMetric().getBytes(getCharset()).length > getMetricNameLimit()) {
            throw new IllegalArgumentException("Cannot add metric " + name
                    + "; name exceeds length limit");
        }
        if (name.hasInstance()) {
            if (!supportsInstances()) {
                throw new IllegalArgumentException("Metric name " + name
                        + " contains an instance but this writer does not support them");
            }
        }
        PcpMetricInfo metricInfo = metricNames.get(name.getMetric());
        InstanceDomain domain = null;
        Instance instance = null;
        
        if (name.hasInstance()) {
            domain = getInstanceDomain(name.getInstanceDomainTag());
            instance = domain.getInstance(name.getInstance());
        }
        
        if (metricInfo == null) {
            metricInfo = new PcpMetricInfo(name.getMetric(), domain, pcpType);
            metricNames.put(name.getMetric(), metricInfo);
        } else {
            if (domain != metricInfo.domain) {
                throw new IllegalArgumentException("Metric name " + name
                        + " does not match previously specified instance layout");
            }
            if (!pcpType.equals(metricInfo.typeHandler)) {
                throw new IllegalArgumentException("Metric name " + name
                        + " cannot use different type handlers for different instances");
            }
        }
        PcpValueInfo info = new PcpValueInfo(name, metricInfo, instance, initialValue);
        metricData.put(name, info);
    }

    protected abstract boolean supportsInstances();

    protected ByteBuffer initialiseBuffer(File file, int length) throws IOException {
        RandomAccessFile fos = null;
        try {
            fos = new RandomAccessFile(file, "rw");
            fos.setLength(0);
            fos.setLength(length);
            ByteBuffer tempDataFile = fos.getChannel().map(MapMode.READ_WRITE, 0, length);
            tempDataFile.order(ByteOrder.nativeOrder());
            fos.close();

            return tempDataFile;
        } finally {
            if (fos != null) {
                fos.close();
            }
        }
    }

    /*
     * (non-Javadoc)
     * @see com.custardsource.parfait.pcp.PcpWriter#start()
     */
    public void start() throws IOException {
        if (started) {
            throw new IllegalStateException("Writer is already started");
        }
        if (metricData.isEmpty()) {
            throw new IllegalStateException("Cannot create an MMV file with no metrics");
        }
        initialiseOffsets();
        dataFileBuffer = initialiseBuffer(dataFile, getFileLength());
        populateDataBuffer(dataFileBuffer, metricData.values());

        started = true;
    }

    protected abstract void initialiseOffsets();

    protected abstract void populateDataBuffer(ByteBuffer dataFileBuffer,
            Collection<PcpValueInfo> metricInfos) throws IOException;

    protected abstract int getMetricNameLimit();

    protected abstract Charset getCharset();

    protected abstract int getFileLength();

    protected static class PcpMetricInfo {
        private final String metricName;
        private final InstanceDomain domain;
        private final TypeHandler<?> typeHandler;
        private int offset;
        
        public PcpMetricInfo(String metricName, InstanceDomain domain, TypeHandler<?> typeHandler) {
            this.metricName = metricName;
            this.domain = domain;
            this.typeHandler = typeHandler;
        }

        public int getOffset() {
            return offset;
        }

        public void setOffset(int offset) {
            this.offset = offset;
        }
        
        public String getMetricName() {
            return metricName;
        }

        public TypeHandler<?> getTypeHandler() {
            return typeHandler;
        }

        public InstanceDomain getInstanceDomain() {
            return domain;
        }

        
    }
    
    protected static class PcpValueInfo {

        public PcpValueInfo(MetricName metricName, PcpMetricInfo metricInfo, Instance instance, Object initialValue) {
            this.metricName = metricName;
            this.metricInfo = metricInfo;
            this.instance = instance;
            this.initialValue = initialValue;
        }

        private final MetricName metricName;
        private final Object initialValue;
        private final PcpMetricInfo metricInfo;
        private Instance instance;
        private PcpOffset offsets;

        public MetricName getMetricName() {
            return metricName;
        }

        public PcpOffset getOffsets() {
            return offsets;
        }

        public void setOffsets(PcpOffset offsets) {
            this.offsets = offsets;
        }

        public TypeHandler<?> getTypeHandler() {
            return metricInfo.typeHandler;
        }

        public Object getInitialValue() {
            return initialValue;
        }

        public int getInstanceOffset() {
            return instance == null ? 0 : instance.offset;
        }

        public int getDescriptorOffset() {
            return metricInfo.getOffset();
        }

    }

    protected static class PcpOffset {
        private final int dataBlockOffset;
        private final int dataValueOffset;

        public PcpOffset(int dataBlockOffset, int dataOffset) {
            this.dataBlockOffset = dataBlockOffset;
            this.dataValueOffset = dataOffset;
        }

        public int dataValueOffset() {
            return dataValueOffset;
        }

        public int dataBlockOffset() {
            return dataBlockOffset;
        }

    }

    private Map<String, InstanceDomain> instanceDomainsByName = new HashMap<String, InstanceDomain>();
    private Map<Integer, InstanceDomain> instanceDomainsById = new HashMap<Integer, InstanceDomain>();

    // TODO don't synchronize - concurrentmap
    protected synchronized InstanceDomain getInstanceDomain(String name) {
        InstanceDomain domain = instanceDomainsByName.get(name);
        if (domain == null) {
            int id = calculateId(name, instanceDomainsById.keySet());
            domain = new InstanceDomain(name, id);
            instanceDomainsByName.put(name, domain);
            instanceDomainsById.put(id, domain);
        }
        return domain;
    }

    private static int calculateId(String name, Set<Integer> usedIds) {
        int value = name.hashCode();
        // Math.abs(MIN_VALUE) == MIN_VALUE, better deal with that just in case...
        if (value == Integer.MIN_VALUE) {
            value++;
        }
        value = Math.abs(value);
        while (usedIds.contains(value)) {
            value = Math.abs(value + 1);
        }
        return value;
    }

    protected static class InstanceDomain {
        private final String name;
        private final int id;
        private Map<String, Instance> instancesByName = new HashMap<String, Instance>();
        private Map<Integer, Instance> instancesById = new HashMap<Integer, Instance>();

        private InstanceDomain(String name, int id) {
            this.name = name;
            this.id = id;
        }

        // TODO don't synchronize - concurrentmap
        public synchronized Instance getInstance(String name) {
            Instance instance = instancesByName.get(name);
            if (instance == null) {
                int id = calculateId(name, instancesById.keySet());
                instance = new Instance(name, id);
                instancesByName.put(name, instance);
                instancesById.put(id, instance);
            }
            return instance;
        }

        @Override
        public String toString() {
            return name + " (" + id + ") " + instancesByName.values().toString();
        }

        public int getId() {
            return id;
        }
    }

    protected static class Instance {
        private final String name;
        private final int id;
        private int offset;

        private Instance(String name, int id) {
            this.name = name;
            this.id = id;
        }

        @Override
        public String toString() {
            return name + " (" + id + ")";
        }

        public int getOffset() {
            return offset;
        }

        public void setOffset(int offset) {
            this.offset = offset;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
    
    protected Collection<Instance> getInstances() {
        Collection<Instance> instances = new ArrayList<Instance>();
        for (InstanceDomain domain : instanceDomainsByName.values()) {
            instances.addAll(domain.instancesByName.values());
        }
        return instances;
    }

    protected Collection<PcpValueInfo> getValueInfos() {
        return metricData.values();
    }
    
    protected Collection<PcpMetricInfo> getMetricInfos() {
        return metricNames.values();
    }
    

    public static void main(String[] args) throws Exception {
        BasePcpWriter writer = new PcpMmvWriter(new File("/tmp/xmmv"));
        InstanceDomain id;
        id = writer.getInstanceDomain("aconex.smurfs");
        id = writer.getInstanceDomain("aconex.tasks");
        id = writer.getInstanceDomain("aconex.controllers");
        id = writer.getInstanceDomain("aconex.controllers");
        id.getInstance("TaskControl");
        id.getInstance("TaskControl");
        id.getInstance("SearchControlledDocControl");
        writer.getInstanceDomain("aconex.tasks").getInstance("");
        System.out.println(writer.instanceDomainsByName.values());
    }
}
