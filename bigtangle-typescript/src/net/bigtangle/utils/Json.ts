import { ObjectMapper, SerializationFeature, DeserializationFeature } from 'jackson-js';

export class Json {
    public static jsonmapper(): ObjectMapper {
        const mapper = new ObjectMapper();
 

        // The jackson-js library does not support ordering map entries by keys.
        // mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        
        // The jackson-js library does not support setting a date format.
        // const outputFormat = new SimpleDateFormat("dd MMM yyyy");
        // mapper.setDateFormat(outputFormat);

        // The jackson-js library does not support setting serialization inclusion.
        // mapper.setSerializationInclusion(Include.NON_EMPTY);
        // mapper.setSerializationInclusion(Include.NON_NULL);

        return mapper;
    }
}