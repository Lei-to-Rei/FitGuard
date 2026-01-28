package com.example.fitguard.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseFeatureActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    abstract fun getViewBinding(): VB
    abstract fun getFeatureTitle(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = getViewBinding()
        setContentView(binding.root)

        setupToolbar()
        setupFeature()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(com.example.fitguard.R.id.toolbar))
        supportActionBar?.apply {
            title = getFeatureTitle()
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    abstract fun setupFeature()

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}