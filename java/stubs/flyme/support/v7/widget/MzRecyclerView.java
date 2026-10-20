package flyme.support.v7.widget;
public class MzRecyclerView extends androidx.recyclerview.widget.RecyclerView {
    public MzRecyclerView(android.content.Context c) { super(c); }
    /** Adapter extension (R8 name m): isEnabled = row reacts to touch (else no selector), d = checkable. */
    public interface m {
        boolean d(int position);
        boolean isEnabled(int position);
    }
}
