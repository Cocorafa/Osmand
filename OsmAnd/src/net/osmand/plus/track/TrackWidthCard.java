package net.osmand.plus.track;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.slider.Slider;

import net.osmand.AndroidUtils;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.dialogs.GpxAppearanceAdapter;
import net.osmand.plus.dialogs.GpxAppearanceAdapter.AppearanceListItem;
import net.osmand.plus.dialogs.GpxAppearanceAdapter.GpxAppearanceAdapterType;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.util.Algorithms;

import java.util.List;

public class TrackWidthCard extends BaseCard {

	private final static String CUSTOM_WIDTH = "custom_width";
	private final static int CUSTOM_WIDTH_MIN = 1;
	private final static int CUSTOM_WIDTH_MAX = 24;

	private TrackDrawInfo trackDrawInfo;

	private AppearanceListItem selectedItem;
	private List<AppearanceListItem> appearanceItems;

	private GpxWidthAdapter widthAdapter;

	public TrackWidthCard(MapActivity mapActivity, TrackDrawInfo trackDrawInfo) {
		super(mapActivity);
		this.trackDrawInfo = trackDrawInfo;
		appearanceItems = getWidthAppearanceItems();
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.track_width_card;
	}

	@Override
	protected void updateContent() {
		updateHeader();
		updateCustomWidthSlider();

		widthAdapter = new GpxWidthAdapter(appearanceItems);
		RecyclerView groupRecyclerView = view.findViewById(R.id.recycler_view);
		groupRecyclerView.setAdapter(widthAdapter);
		groupRecyclerView.setLayoutManager(new LinearLayoutManager(app, RecyclerView.HORIZONTAL, false));

		AndroidUiHelper.updateVisibility(view.findViewById(R.id.top_divider), isShowDivider());
	}

	public void updateItems() {
		if (widthAdapter != null) {
			widthAdapter.notifyDataSetChanged();
		}
	}

	private AppearanceListItem getSelectedItem() {
		if (selectedItem == null) {
			String selectedWidth = trackDrawInfo.getWidth();
			for (AppearanceListItem item : appearanceItems) {
				if (Algorithms.objectEquals(item.getValue(), selectedWidth)
						|| Algorithms.isEmpty(selectedWidth) && Algorithms.isEmpty(item.getValue())
						|| Algorithms.isInt(selectedWidth) && CUSTOM_WIDTH.equals(item.getAttrName())) {
					selectedItem = item;
					break;
				}
			}
		}
		return selectedItem;
	}

	private List<AppearanceListItem> getWidthAppearanceItems() {
		List<AppearanceListItem> items = GpxAppearanceAdapter.getAppearanceItems(app, GpxAppearanceAdapterType.TRACK_WIDTH);

		String selectedWidth = trackDrawInfo.getWidth();
		String customWidth = !Algorithms.isEmpty(selectedWidth) && Algorithms.isInt(selectedWidth) ? selectedWidth : String.valueOf(CUSTOM_WIDTH_MIN);

		items.add(new AppearanceListItem(CUSTOM_WIDTH, customWidth, app.getString(R.string.shared_string_custom)));
		return items;
	}

	private void updateHeader() {
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.icon), false);

		TextView titleView = view.findViewById(R.id.title);
		titleView.setText(R.string.select_track_width);

		TextView descriptionView = view.findViewById(R.id.description);
		descriptionView.setText(getSelectedItem().getLocalizedValue());
	}

	private void updateCustomWidthSlider() {
		if (CUSTOM_WIDTH.equals(getSelectedItem().getAttrName())) {
			Slider widthSlider = view.findViewById(R.id.width_slider);

			widthSlider.setValueTo(CUSTOM_WIDTH_MAX);
			widthSlider.setValueFrom(CUSTOM_WIDTH_MIN);

			((TextView) view.findViewById(R.id.width_value_min)).setText(String.valueOf(CUSTOM_WIDTH_MIN));
			((TextView) view.findViewById(R.id.width_value_max)).setText(String.valueOf(CUSTOM_WIDTH_MAX));

			String width = getSelectedItem().getValue();
			if (!Algorithms.isEmpty(width) && Algorithms.isInt(width)) {
				try {
					widthSlider.setValue(Integer.parseInt(width));
				} catch (NumberFormatException e) {
					widthSlider.setValue(1);
				}
			} else {
				widthSlider.setValue(1);
			}

			final TextView selectedCustomWidth = view.findViewById(R.id.width_value_tv);
			widthSlider.addOnChangeListener(new Slider.OnChangeListener() {
				@Override
				public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
					if (fromUser) {
						String valueStr = String.valueOf((int) value);
						selectedCustomWidth.setText(valueStr);
						getSelectedItem().setValue(valueStr);
						setGpxWidth(valueStr);
					}
				}
			});
			UiUtilities.setupSlider(widthSlider, nightMode, null);
			AndroidUiHelper.updateVisibility(view.findViewById(R.id.slider_container), true);
		} else {
			AndroidUiHelper.updateVisibility(view.findViewById(R.id.slider_container), false);
		}
	}

	private void setGpxWidth(String width) {
		trackDrawInfo.setWidth(width);
		mapActivity.refreshMap();
	}

	private class GpxWidthAdapter extends RecyclerView.Adapter<TrackAppearanceViewHolder> {

		private List<AppearanceListItem> items;

		private GpxWidthAdapter(List<AppearanceListItem> items) {
			this.items = items;
		}

		@NonNull
		@Override
		public TrackAppearanceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater themedInflater = UiUtilities.getInflater(parent.getContext(), nightMode);
			View view = themedInflater.inflate(R.layout.point_editor_group_select_item, parent, false);
			view.getLayoutParams().width = app.getResources().getDimensionPixelSize(R.dimen.gpx_group_button_width);
			view.getLayoutParams().height = app.getResources().getDimensionPixelSize(R.dimen.gpx_group_button_height);

			TrackAppearanceViewHolder holder = new TrackAppearanceViewHolder(view);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				AndroidUtils.setBackground(app, holder.button, nightMode, R.drawable.ripple_solid_light_6dp,
						R.drawable.ripple_solid_dark_6dp);
			}
			return holder;
		}

		@Override
		public void onBindViewHolder(@NonNull final TrackAppearanceViewHolder holder, int position) {
			AppearanceListItem item = items.get(position);
			holder.title.setText(item.getLocalizedValue());

			updateButtonBg(holder, item);
			updateWidthIcon(holder, item);

			holder.itemView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					int prevSelectedPosition = getItemPosition(getSelectedItem());
					selectedItem = items.get(holder.getAdapterPosition());
					notifyItemChanged(holder.getAdapterPosition());
					notifyItemChanged(prevSelectedPosition);

					setGpxWidth(selectedItem.getValue());

					updateHeader();
					updateCustomWidthSlider();
				}
			});
		}

		private void updateWidthIcon(TrackAppearanceViewHolder holder, AppearanceListItem item) {
			int color = trackDrawInfo.getColor();

			int iconId;
			if (CUSTOM_WIDTH.equals(item.getAttrName())) {
				iconId = R.drawable.ic_action_settings;
				color = AndroidUtils.getColorFromAttr(holder.itemView.getContext(), R.attr.active_color_basic);
			} else {
				iconId = GpxAppearanceAdapter.getWidthIconId(item.getValue());
			}
			holder.icon.setImageDrawable(app.getUIUtilities().getPaintedIcon(iconId, color));
		}

		private void updateButtonBg(TrackAppearanceViewHolder holder, AppearanceListItem item) {
			GradientDrawable rectContourDrawable = (GradientDrawable) AppCompatResources.getDrawable(app, R.drawable.bg_select_group_button_outline);
			if (rectContourDrawable != null) {
				if (getSelectedItem() != null && getSelectedItem().equals(item)) {
					int strokeColor = ContextCompat.getColor(app, nightMode ? R.color.active_color_primary_dark : R.color.active_color_primary_light);
					rectContourDrawable.setStroke(AndroidUtils.dpToPx(app, 2), strokeColor);
				} else {
					int strokeColor = ContextCompat.getColor(app, nightMode ? R.color.stroked_buttons_and_links_outline_dark
							: R.color.stroked_buttons_and_links_outline_light);
					rectContourDrawable.setStroke(AndroidUtils.dpToPx(app, 1), strokeColor);
				}
				holder.button.setImageDrawable(rectContourDrawable);
			}
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		int getItemPosition(AppearanceListItem name) {
			return items.indexOf(name);
		}
	}
}