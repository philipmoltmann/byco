/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidapp.byco.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.view.View.*
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidapp.byco.background.Prefetcher
import androidapp.byco.background.Prefetcher.ProcessPriority.FOREGROUND
import androidapp.byco.data.PreviousRide
import androidapp.byco.data.ThumbnailRepository
import androidapp.byco.lib.R
import androidapp.byco.lib.databinding.PreviousRideListItemBinding
import androidapp.byco.lib.databinding.PreviousRidesActivityBinding
import androidapp.byco.util.isDarkMode
import androidapp.byco.util.isLocaleUsingMiles
import androidapp.byco.util.makeVisibleIf
import androidapp.byco.util.plus
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.text.DateFormat.SHORT
import java.util.*

/**
 * Shows list of previous rides
 */
class PreviousRidesActivity : AppCompatActivity() {
    private val viewModel by viewModels<PreviousRidesViewModel>()
    private lateinit var binding: PreviousRidesActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.registerActivityResultsContracts(this)

        binding = PreviousRidesActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        class PreviousRideHolder(
            val view: PreviousRideListItemBinding,
        ) : RecyclerView.ViewHolder(view.root) {
            var ride: PreviousRide? = null
            var thumbnailProvider: LiveData<Bitmap>? = null

            val thumbnailObserver: Observer<Bitmap> = Observer<Bitmap> { thumbnail ->
                view.map.setImageBitmap(thumbnail)
            }

            var currentlyShownRideObserver: Observer<PreviousRide?>? = null
        }

        (binding.rides as RecyclerView).adapter = object : RecyclerView.Adapter<PreviousRideHolder>() {
            private var previousRides: List<PreviousRide> = emptyList()

            init {
                fun updatePreviousRides() {
                    previousRides = viewModel.previousRides.value ?: emptyList()
                    notifyDataSetChanged()
                }

                viewModel.previousRides.observe(this@PreviousRidesActivity) {
                    updatePreviousRides()
                }

                viewModel.recentlyAddedRide.observe(this@PreviousRidesActivity) { addedRide ->
                    updatePreviousRides()

                    val position = previousRides.indexOf(addedRide)
                    if (position >= 0) {
                        ((binding.rides as RecyclerView).layoutManager!! as LinearLayoutManager).scrollToPositionWithOffset(
                            position,
                            0
                        )
                    }
                }
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): PreviousRideHolder {
                val binding =
                    PreviousRideListItemBinding.inflate(
                        layoutInflater,
                        binding.rides as RecyclerView,
                        false
                    )
                binding.previousRide.clipToOutline = true

                return PreviousRideHolder(binding)
            }

            override fun onBindViewHolder(holder: PreviousRideHolder, position: Int) {
                val ride = previousRides[position]

                // Don't clear thumbnail if the holder is reused for the same ride
                if (holder.ride != ride) {
                    holder.view.map.setImageDrawable(null)
                }

                // Thumbnail is loaded from live data by [PreviousRideHolder.thumbnailProvider]
                holder.thumbnailProvider?.removeObserver(holder.thumbnailObserver)
                holder.thumbnailProvider =
                    ThumbnailRepository[application].getThumbnail(ride, isDarkMode())
                holder.thumbnailProvider!!.observe(
                    this@PreviousRidesActivity,
                    holder.thumbnailObserver
                )

                holder.view.title.text = ride.title

                holder.view.editTitle.setOnClickListener {
                    holder.view.editableTitle.visibility = VISIBLE
                    holder.view.title.visibility = GONE
                    holder.view.editTitle.visibility = INVISIBLE

                    holder.view.editableTitle.setText(ride.title)

                    holder.view.editableTitle.requestFocus()

                    // Not sure why keyboard does not appear ... well ... just force it
                    getSystemService(InputMethodManager::class.java).showSoftInput(
                        holder.view.editableTitle, 0
                    )
                }

                holder.view.editableTitle.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newTitle = holder.view.editableTitle.text.toString()

                        if (ride.title != newTitle) {
                            holder.view.title.text = newTitle
                            viewModel.changeTitle(ride, newTitle)
                        }

                        holder.view.editableTitle.visibility = GONE
                        holder.view.title.visibility = VISIBLE
                        holder.view.editTitle.visibility = VISIBLE

                        // We forced the keyboard in the on editTitle.setOnClickListener, hence need
                        // to revert it
                        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(
                            holder.view.editableTitle.windowToken, 0
                        )
                    }
                }

                holder.view.duration.visibility = GONE
                ride.duration?.let { duration ->
                    holder.view.duration.text = duration.format(this@PreviousRidesActivity)
                    holder.view.duration.makeVisibleIf(!duration.isNone)
                }

                ride.distance.let {
                    // Based on locale, not current country. I.e. the user gets the unit they are
                    // used to, no matter where they are
                    holder.view.distance.text =
                        it.format(this@PreviousRidesActivity, isLocaleUsingMiles())
                }

                holder.view.date.visibility = INVISIBLE
                ride.time?.let { time ->
                    holder.view.date.text =
                        DateFormat.getDateTimeInstance(SHORT, SHORT).format(Date(time))
                    holder.view.date.visibility = VISIBLE
                }

                holder.view.showOnMapCoach.makeVisibleIf(position == 0)

                holder.view.delete.setOnClickListener {
                    AlertDialog.Builder(this@PreviousRidesActivity).setMessage(
                        getString(
                            R.string.confirm_delete_ride,
                            ride.title ?: getString(R.string.unknown_ride_name)
                        )
                    ).setPositiveButton(
                        R.string.delete_button_desc
                    ) { _, _ ->
                        viewModel.delete(ride)
                    }.setNegativeButton(R.string.button_cancel) { _, _ ->
                        // do nothing
                    }.show()
                }

                holder.view.share.setOnClickListener {
                    viewModel.share(this@PreviousRidesActivity, ride)
                }

                holder.currentlyShownRideObserver?.let {
                    viewModel.shownRide.removeObserver(
                        it
                    )
                }

                holder.view.showHide.setImageDrawable(
                    ContextCompat.getDrawable(
                        this@PreviousRidesActivity,
                        R.drawable.ic_baseline_eye_off_24
                    )
                )

                holder.currentlyShownRideObserver =
                    Observer<PreviousRide?> { currentlyShownRide ->
                        if (currentlyShownRide == ride) {
                            holder.view.showHide.setImageDrawable(
                                ContextCompat.getDrawable(
                                    this@PreviousRidesActivity,
                                    R.drawable.ic_baseline_eye_24
                                )
                            )
                            holder.view.showHide.setColorFilter(getColor(R.color.accent))
                            holder.view.delete.visibility = GONE
                        } else {
                            holder.view.showHide.setImageDrawable(
                                ContextCompat.getDrawable(
                                    this@PreviousRidesActivity,
                                    R.drawable.ic_baseline_eye_off_24
                                )
                            )
                            holder.view.showHide.colorFilter = holder.view.share.colorFilter
                            holder.view.delete.visibility = VISIBLE
                        }
                    }.also {
                        viewModel.shownRide.observe(this@PreviousRidesActivity, it)
                    }

                holder.view.showHide.setOnClickListener {
                    holder.view.showOnMapCoach.neverShowAgain()

                    if (viewModel.shownRide.value == ride) {
                        Toast.makeText(
                            this@PreviousRidesActivity,
                            getString(
                                R.string.hide_visible_track,
                                ride.title ?: getString(R.string.unknown_ride_name)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.show(null)
                    } else {
                        Toast.makeText(
                            this@PreviousRidesActivity,
                            getString(
                                R.string.show_visible_track,
                                ride.title ?: getString(R.string.unknown_ride_name)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.show(ride)
                    }
                }

                holder.ride = ride
            }

            override fun onViewRecycled(holder: PreviousRideHolder) {
                super.onViewRecycled(holder)

                holder.thumbnailProvider?.removeObserver(holder.thumbnailObserver)
            }

            override fun getItemCount() = previousRides.size
        }

        (viewModel.previousRides + viewModel.titleFilter).observe(this) {
            binding.emptyView.makeVisibleIf(viewModel.previousRides.value?.isEmpty() ?: false && viewModel.titleFilter.value == null)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Allow editable_title views to release focus when clicked outside.
        // @see onFocusChangedListener on these views
        currentFocus?.let { currentFocus ->
            if (currentFocus.id == R.id.editable_title
                && ev.action == MotionEvent.ACTION_DOWN
            ) {
                val focusedTopLeft = IntArray(2)
                currentFocus.getLocationOnScreen(focusedTopLeft)

                if (ev.x.toInt() !in focusedTopLeft[0]..(focusedTopLeft[0] + currentFocus.width)
                    || ev.y.toInt() !in focusedTopLeft[1]..(focusedTopLeft[1] + currentFocus.height)
                ) {
                    currentFocus.clearFocus()
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()

        Prefetcher[application].start(FOREGROUND)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.previous_rides, menu)

        val expandListener = object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                // empty
                return true
            }
        }

        val actionMenuItem = menu.findItem(R.id.action_search)
        val searchView = actionMenuItem!!.actionView as SearchView
        actionMenuItem.setOnActionExpandListener(expandListener)

        if (viewModel.titleFilter.value != null) {
            actionMenuItem.expandActionView()
            searchView.setQuery(viewModel.titleFilter.value, false)
        }

        searchView.queryHint = getString(R.string.previous_rides_query_hint)

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // do nothing
                return true
            }

            override fun onQueryTextChange(filter: String?): Boolean {
                viewModel.titleFilter.value = filter

                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_ride -> {
                viewModel.add()
                true
            }
            else -> false
        }
    }

    override fun onStop() {
        super.onStop()

        Prefetcher[application].stop(FOREGROUND)
    }
}
