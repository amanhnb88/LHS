package id.lhspapua.data

/** One queued key can absorb rapid input; taking it establishes the next durable write boundary. */
class WriteBuffer<T> {
    private val values=linkedMapOf<String,T>()
    @Synchronized fun replace(key:String,value:T):Boolean { val first=key !in values;values[key]=value;return first }
    @Synchronized fun take(key:String):T?=values.remove(key)
    @Synchronized fun size()=values.size
}
