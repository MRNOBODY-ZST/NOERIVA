package io.noeriva.query;
public record RollupKey(String organizationId,String deviceId,String interfaceId,String sourceId,String direction) {
    public RollupKey {
        QueryService.validateId(organizationId);QueryService.validateId(deviceId);
        QueryService.validateId(interfaceId);QueryService.validateId(sourceId);
        if(!java.util.Set.of("rx","tx").contains(direction)) throw new IllegalArgumentException("Direction must be rx or tx");
    }
}
