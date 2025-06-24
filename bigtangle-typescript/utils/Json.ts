export class Json {
  public static stringify(obj: any): string {
    // Create a replacer function to filter out empty and null values
    const replacer = (key: string, value: any) => {
      // Exclude null and undefined values
      if (value === null || value === undefined) {
        return undefined;
      }
      
      // Exclude empty arrays
      if (Array.isArray(value) && value.length === 0) {
        return undefined;
      }
      
      // Exclude empty objects
      if (typeof value === 'object' && Object.keys(value).length === 0) {
        return undefined;
      }
      
      return value;
    };

    // Stringify with pretty printing and sorted keys
    return JSON.stringify(
      obj,
      replacer,
      2  // Indent with 2 spaces for pretty printing
    );
  }

  public static parse(json: string): any {
    return JSON.parse(json);
  }
}
