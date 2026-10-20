package androidx.recyclerview.widget;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
public class RecyclerView extends ViewGroup {
    public RecyclerView(Context c) { super(c); }
    public RecyclerView(Context c, AttributeSet a) { super(c, a); }
    @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {}
    public void setAdapter(Adapter a) {}
    public Adapter getAdapter() { return null; }
    public void setLayoutManager(LayoutManager lm) {}
    public void setItemAnimator(ItemAnimator a) {}
    public void scrollToPosition(int p) {}
    public void addOnScrollListener(OnScrollListener l) {}
    @Override public int computeVerticalScrollOffset() { return 0; }
    public static abstract class OnScrollListener {
        public void onScrollStateChanged(RecyclerView rv, int state) {}
        public void onScrolled(RecyclerView rv, int dx, int dy) {}
    }
    public static abstract class Adapter<VH extends ViewHolder> {
        public abstract VH onCreateViewHolder(ViewGroup parent, int viewType);
        public abstract void onBindViewHolder(VH holder, int position);
        public abstract int getItemCount();
        public int getItemViewType(int position) { return 0; }
        public final void notifyDataSetChanged() {}
        public final void notifyItemChanged(int position) {}
    }
    public static abstract class ViewHolder {
        public final View itemView;
        public ViewHolder(View v) { itemView = v; }
        public final int getAdapterPosition() { return 0; }
    }
    public static abstract class LayoutManager {}
    public static abstract class ItemAnimator {}
}
