package com.greyhaze.medialauncher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.greyhaze.medialauncher.apps.AppsDrawerAdapter
import com.greyhaze.medialauncher.audiobooks.AudiobooksFragment
import com.greyhaze.medialauncher.music.MusicFragment
import com.greyhaze.medialauncher.podcasts.PodcastsFragment

/**
 * Fragment to display the 2x2 home screen grid for MediaLauncher.
 */
class HomeScreenFragment : Fragment() {
    private var recyclerView: RecyclerView? = null
    private var adapter: RecyclerView.Adapter<*>? = null
    private var layoutManager: RecyclerView.LayoutManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.appDrawer_recylerView)
        adapter = AppsDrawerAdapter(requireContext()) { target ->
            onHomeTileClicked(target)
        }
        layoutManager = GridLayoutManager(context, 2)
        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter

        view.findViewById<ImageView>(R.id.settings_button).setOnClickListener {
            context?.startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        // Hide legacy fragment-scoped cards; global_now_playing_card in MainActivity handles all screens
        view.findViewById<View>(R.id.permission_prompt_card)?.visibility = View.GONE
        view.findViewById<View>(R.id.now_playing_card)?.visibility = View.GONE
    }

    private fun onHomeTileClicked(target: String) {
        val targetFragment: Fragment? = when (target) {
            AppsDrawerAdapter.TILE_AUDIOBOOK -> AudiobooksFragment()
            AppsDrawerAdapter.TILE_MUSIC -> MusicFragment()
            AppsDrawerAdapter.TILE_PODCAST -> PodcastsFragment()
            else -> null
        }

        if (targetFragment != null) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, targetFragment)
                .addToBackStack(target)
                .commit()
        } else {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(target)
            if (intent != null) {
                startActivity(intent)
            }
        }
    }

    override fun onDestroyView() {
        recyclerView = null
        adapter = null
        layoutManager = null
        super.onDestroyView()
    }
}
